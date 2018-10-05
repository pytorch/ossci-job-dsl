import ossci.DockerUtil
import ossci.EmailUtil
import ossci.GitUtil
import ossci.JobUtil
import ossci.MacOSUtil
import ossci.ParametersUtil
import ossci.PhaseJobUtil
import ossci.caffe2.Images
import ossci.caffe2.DockerVersion


def nightliesUploadedBasePath = 'nightlies-uploaded'
def uploadCondaBasePath = 'conda-packages'
def uploadPipBasePath = 'pip-packages'
def uploadLibtorchBasePath = "libtorch-packages"
folder(nightliesUploadedBasePath) {
  description 'Jobs to see if the nightly packages were uploaded for the day'
}
folder(uploadPipBasePath) {
  description 'Jobs for nightly uploads of Pip packages'
}
folder(uploadCondaBasePath) {
  description 'Jobs for nightly uploads of Conda packages'
}
folder(uploadLibtorchBasePath) {
  description 'Jobs for nightly upload of libtorch packages'
}

//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////
// README
// This contains the definitions of both -build-upload jobs and for -uploaded
// jobs.
//
// *-build-upload jobs are legacy, but might be useful for debugging. Once upon
// a time we wanted to build all the Pytorch nightly binaries on jenkins and we
// set up these jobs to do that. These jobs are complete, allow for building of
// arbitrary branches with arbitrary versions and uploading to arbitrary s3
// locations and have the credentials to do so, but then we realized that
// jenkins is not safe enough to release our official binaries. But these jobs
// can still be useful because they are more parallel (1 job per machine as
// opposed to 12 per machine on the cluster), so if you want to test out some
// quick changes or upload non-official binaries to some other s3 location then
// these are still helpful.
//
// -uploaded jobs just check that the nightly jobs uploaded for the day, by
// downloading them from the public repos and checking the date in the version.
// These run at 9am everday, which should give 3 hours for each job to finish
// in the cluster (jobs start at 1am and the default timeout is less than 3
// hours).
// 
//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////


//////////////////////////////////////////////////////////////////////////////
// Uploaded jobs
//////////////////////////////////////////////////////////////////////////////
Images.allNightlyBuildEnvironments.each {
  def buildEnvironment = it

  job("${nightliesUploadedBasePath}/${buildEnvironment}-uploaded") {
    def buildForMac = buildEnvironment.contains('macos')

    // Delegate to either a Mac or a Linux machine
    if (buildForMac) {
      JobUtil.common(delegate, 'osx')
    } else {
      if (buildEnvironment.contains('cuda')) {
        JobUtil.common(delegate, 'docker && gpu')
      } else {
        JobUtil.common(delegate, 'docker && cpu')
      }
    }

    wrappers {
      credentialsBinding {
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    }

    parameters {
      ParametersUtil.DATE(delegate)
      ParametersUtil.NIGHTLIES_DATE_PREAMBLE(delegate)
    }

    steps {
      def packageType = buildEnvironment.contains('conda') ? 'conda' : 'wheel'

      // Set DESIRED_CUDA to 'cpu' or 'cu##'
      def desiredCuda = 'cpu'
      def cudaVersion = ''
      if (buildEnvironment.contains('cuda')) {
        def cudaVer = buildEnvironment =~ /cuda(\d\d)/
        desiredCuda = 'cu' + cudaVer[0][1]
        cudaVersion = 'native'
      }

      // Set Python to 'cp##-cp##mu?' or '#.#'
      def pyVersion = ''
      if (packageType == 'conda') {
        pyVersion = buildEnvironment =~ /^conda(\d.\d)/
      } else if (buildForMac) {
        pyVersion = buildEnvironment =~ /^pip-(\d.\d)/
      } else {
        pyVersion = buildEnvironment =~ /cp\d\d-cp(\d\dmu?)/
      }

      // Set Docker image
      def dockerImage = ''
      if (!buildForMac) {
        dockerImage = 'soumith/manylinux-cuda' + desiredCuda.substring(2)
        if (buildEnvironment.contains('conda')) {
          dockerImage = 'soumith/conda-cuda'
        }
      }

      // Set the script before calling into it so that we don't have to copy it
      // across jobs
      def uploaded_job_script = '''
set -ex

# Use today's date if none is given
if [[ "$DATE" == 'today' ]]; then
    DATE="$(date +%Y%m%d)"
fi

# Determine package name
if [[ "$PACKAGE_TYPE" == *wheel ]]; then
  package_name='torch-nightly'
elif [[ "$DESIRED_CUDA" == 'cpu' ]]; then
  package_name='pytorch-nightly-cpu'
else
  package_name='pytorch-nightly'
fi
package_name_and_version="${package_name}==${NIGHTLY_VERSION_PREAMBLE}${DATE}"

# Install Anaconda if we're on Mac
if [[ "$(uname)" == 'Darwin' ]]; then
  rm -rf ${TMPDIR}/anaconda
  curl -o ${TMPDIR}/anaconda.sh https://repo.continuum.io/miniconda/Miniconda3-latest-MacOSX-x86_64.sh
  /bin/bash ${TMPDIR}/anaconda.sh -b -p ${TMPDIR}/anaconda
  rm -f ${TMPDIR}/anaconda.sh
  export PATH="${TMPDIR}/anaconda/bin:${PATH}"
  source ${TMPDIR}/anaconda/bin/activate
fi

# Switch to the desired python
if [[ "$PACKAGE_TYPE" == 'conda' || "$(uname)" == 'Darwin' ]]; then
  conda create -yn test python="$DESIRED_PYTHON" && source activate test
  conda install -y future numpy protobuf six
else
  export PATH=/opt/python/$DESIRED_PYTHON/bin:$PATH
  pip install future numpy protobuf six
fi

# Switch to the desired CUDA if using the conda-cuda Docker image
if [[ "$PACKAGE_TYPE" == 'conda' ]]; then
  rm -rf /usr/local/cuda || true
  if [[ "$DESIRED_CUDA" != 'cpu' ]]; then
    ln -s "/usr/local/cuda-${DESIRED_CUDA:2:1}.${DESIRED_CUDA:3:1}" /usr/local/cuda
    export CUDA_VERSION=$(ls /usr/local/cuda/lib64/libcudart.so.*|sort|tac | head -1 | rev | cut -d"." -f -3 | rev)
    export CUDNN_VERSION=$(ls /usr/local/cuda/lib64/libcudnn.so.*|sort|tac | head -1 | rev | cut -d"." -f -3 | rev)
  fi
fi

# Print some debugging info
python --version
which python
if [[ "$PACKAGE_TYPE" == 'conda' ]]; then
  conda search -c pytorch "$package_name"
else
  "curl https://download.pytorch.org/whl/nightly/$DESIRED_CUDA/torch_nightly.html"
fi

# Install the package for the requested date
if [[ "$PACKAGE_TYPE" == 'conda' ]]; then
  if [[ "$DESIRED_CUDA" == 'cpu' || "$DESIRED_CUDA" == 'cu90' ]]; then
    conda install -yq -c pytorch "$package_name_and_version
  else
    conda install -yq -c pytorch "cuda${DESIRED_CUDA:2:2}" "$package_name_and_version"
  fi
else
  pip install "$package_name_and_version" \
      -f "https://download.pytorch.org/whl/nightly/$DESIRED_CUDA/torch_nightly.html" \
      --no-cache-dir \
      --no-index \
      -v
fi

# Check that the package's date matches
if [[ "$PACKAGE_TYPE" == 'conda' ]]; then
  uploaded_version="$(conda list pytorch | grep pytorch)"
else
  uploaded_version="$(pip freeze | grep torch)"
fi
if [[ -z "$(echo $uploaded_version | grep $DATE)" ]]; then
    echo "The installed version $uploaded_version doesn't appear to be for the date $DATE"
    exit 1
fi

# Smoke test that it works
python -c 'import torch'
python -c 'from caffe2.python import core'
'''

      environmentVariables {
        env('DESIRED_PYTHON', pyVersion[0][1])
        env('DESIRED_CUDA', desiredCuda)
        env('PACKAGE_TYPE', packageType)
      }
      if (buildForMac) {
        MacOSUtil.sandboxShell delegate, uploaded_job_script
      } else {
        DockerUtil.shell context: delegate,
                image: dockerImage,
                cudaVersion: cudaVersion,
                workspaceSource: "docker",
                usePipDockers: "true",
                script: uploaded_job_script
      } // MacOSUtil.sandboxShell or DockerUtil.shell
    } // steps
  }
} // allNightliesBuildEnvironments --uploaded





//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////
// BUILD_UPLOAD JOBS
//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////

//////////////////////////////////////////////////////////////////////////////
// Mac Conda mac
//////////////////////////////////////////////////////////////////////////////
Images.macCondaBuildEnvironments.each {
  def buildEnvironment = it
  job("${uploadCondaBasePath}/${buildEnvironment}-build-upload") {
  JobUtil.common(delegate, 'osx')
  JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.PYTORCH_REPO(delegate)
      ParametersUtil.PYTORCH_BRANCH(delegate)
      ParametersUtil.TORCH_CONDA_BUILD_FOLDER(delegate, 'pytorch-nightly')
      ParametersUtil.CMAKE_ARGS(delegate)
      ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
      ParametersUtil.RUN_TEST_PARAMS(delegate)
      ParametersUtil.UPLOAD_PACKAGE(delegate, false)
      ParametersUtil.PYTORCH_BUILD_VERSION(delegate, '0.5.0.dev20180913')
      ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '1')
      ParametersUtil.DEBUG(delegate, false)
    }

  wrappers {
    credentialsBinding {
      usernamePassword('ANACONDA_USERNAME', 'CAFFE2_ANACONDA_ORG_ACCESS_TOKEN', 'caffe2_anaconda_org_access_token')
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
    }
  }

  steps {
    GitUtil.mergeStep(delegate)

    // Determine which python version to build
    def condaVersion = buildEnvironment =~ /^conda(\d.\d)/

    // Populate environment
    environmentVariables {
      env('BUILD_ENVIRONMENT', "${buildEnvironment}",)
      env('DESIRED_PYTHON', condaVersion[0][1])
    }

    MacOSUtil.sandboxShell delegate, '''
set -ex

# Jenkins passes DEBUG as a string, change this to what the script expects
if [[ "$DEBUG" == 'true' ]]; then
  export DEBUG=1
else
  unset DEBUG
fi

# TODO do we need these?
# Reinitialize path (see man page for path_helper(8))
eval `/usr/libexec/path_helper -s`
# Fix for xcode-select in jenkins
export DEVELOPER_DIR=/Applications/Xcode9.app/Contents/Developer

# Install Anaconda
rm -rf ${TMPDIR}/anaconda
curl -o ${TMPDIR}/anaconda.sh "https://repo.continuum.io/archive/Anaconda${DESIRED_PYTHON:0:1}-5.0.1-MacOSX-x86_64.sh"
/bin/bash ${TMPDIR}/anaconda.sh -b -p ${TMPDIR}/anaconda
rm -f ${TMPDIR}/anaconda.sh
export PATH="${TMPDIR}/anaconda/bin:${PATH}"
source ${TMPDIR}/anaconda/bin/activate

# Build the conda packages
pushd conda
./build_pytorch.sh cpu $PYTORCH_BUILD_VERSION $PYTORCH_BUILD_NUMBER
'''
    }
  }
} // macCondaBuildEnvironments.each

//////////////////////////////////////////////////////////////////////////////
// Mac Pip
//////////////////////////////////////////////////////////////////////////////
Images.macPipBuildEnvironments.each {
  def buildEnvironment = it
  job("${uploadPipBasePath}/${buildEnvironment}-build-upload") {
  JobUtil.common(delegate, 'osx')
  JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')

  parameters {
    ParametersUtil.GIT_COMMIT(delegate)
    ParametersUtil.GIT_MERGE_TARGET(delegate)
    ParametersUtil.PYTORCH_REPO(delegate, 'pytorch')
    ParametersUtil.PYTORCH_BRANCH(delegate, 'master')
    ParametersUtil.CMAKE_ARGS(delegate)
    ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
    ParametersUtil.RUN_TEST_PARAMS(delegate)
    ParametersUtil.TORCH_PACKAGE_NAME(delegate, 'torch_nightly')
    ParametersUtil.UPLOAD_PACKAGE(delegate, false)
    ParametersUtil.PIP_UPLOAD_FOLDER(delegate, 'nightly/')
    ParametersUtil.PYTORCH_BUILD_VERSION(delegate, '0.5.0.dev20180913')
    ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '1')
    ParametersUtil.OVERRIDE_PACKAGE_VERSION(delegate, '')
    ParametersUtil.DEBUG(delegate, false)
  }

  wrappers {
    credentialsBinding {
      usernamePassword('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'PIP_S3_CREDENTIALS')
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
    }
  }

  steps {
    GitUtil.mergeStep(delegate)

    // Determine which python version to build
      def pyVersion = buildEnvironment =~ /^pip-(\d.\d)/

    // Populate environment
    environmentVariables {
      env('BUILD_ENVIRONMENT', "${buildEnvironment}",)
      env('DESIRED_PYTHON', pyVersion[0][1])
    }

    MacOSUtil.sandboxShell delegate, '''
set -ex

# Parameter checking
###############################################################################
if [[ -z "$AWS_ACCESS_KEY_ID" ]]; then
  echo "Caffe2 Pypi credentials are not propogated correctly."
  exit 1
fi

# Jenkins passes DEBUG as a string, change this to what the script expects
if [[ "$DEBUG" == 'true' ]]; then
  export DEBUG=1
else
  unset DEBUG
fi

# TODO do we need this?
# Reinitialize path (see man page for path_helper(8))
eval `/usr/libexec/path_helper -s`

# Coordinate one output folder across scripts. This variable is expected in
# both scripts called below.
export MAC_PACKAGE_FINAL_FOLDER="$(pwd)/final_mac_pkg"
mkdir -p "\\$MAC_PACKAGE_FINAL_FOLDER"

# Build the wheel
./wheel/build_wheel.sh

# Upload the wheel
if [[ "$UPLOAD_PACKAGE" == true ]]; then
  export PATH=$(pwd)/tmp_conda/bin:$PATH
  conda create -yn aws36 python=3.6
  source activate aws36
  pip install awscli
  ./wheel/upload.sh
fi

# Update html file
# TODO this should be moved to its own job
# ./update_s3_html.sh
'''
    }
  }
} // macPipBuildEnvironments.each


//////////////////////////////////////////////////////////////////////////////
// Mac Libtorch
//////////////////////////////////////////////////////////////////////////////
Images.macLibtorchBuildEnvironments.each {
  def buildEnvironment = it
  job("${uploadLibtorchBasePath}/${buildEnvironment}-build-upload") {
  JobUtil.common(delegate, 'osx')
  JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')

  parameters {
    ParametersUtil.GIT_COMMIT(delegate)
    ParametersUtil.GIT_MERGE_TARGET(delegate)
    ParametersUtil.PYTORCH_REPO(delegate, 'pytorch')
    ParametersUtil.PYTORCH_BRANCH(delegate, 'master')
    ParametersUtil.CMAKE_ARGS(delegate)
    ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
    ParametersUtil.RUN_TEST_PARAMS(delegate)
    ParametersUtil.TORCH_PACKAGE_NAME(delegate, 'torch_nightly')
    ParametersUtil.UPLOAD_PACKAGE(delegate, false)
    ParametersUtil.PIP_UPLOAD_FOLDER(delegate, 'nightly/')
    ParametersUtil.PYTORCH_BUILD_VERSION(delegate, '0.5.0.dev20180913')
    ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '1')
    ParametersUtil.OVERRIDE_PACKAGE_VERSION(delegate, '')
    ParametersUtil.DEBUG(delegate, false)
  }

  wrappers {
    credentialsBinding {
      usernamePassword('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'PIP_S3_CREDENTIALS')
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
    }
  }

  steps {
    GitUtil.mergeStep(delegate)

    // Determine which python version to build
      def pyVersion = buildEnvironment =~ /(cp\d\d-cp\d\dmu?)/

    // Populate environment
    environmentVariables {
      env('BUILD_ENVIRONMENT', "${buildEnvironment}",)
      env('DESIRED_PYTHON', pyVersion[0][1])
    }

    MacOSUtil.sandboxShell delegate, '''
set -ex

# Parameter checking
###############################################################################
if [[ -z "$AWS_ACCESS_KEY_ID" ]]; then
  echo "Caffe2 Pypi credentials are not propogated correctly."
  exit 1
fi

# Jenkins passes DEBUG as a string, change this to what the script expects
if [[ "$DEBUG" == 'true' ]]; then
  export DEBUG=1
else
  unset DEBUG
fi
# TODO fix images.groovy instead of this ugly hack
desired_python="${DESIRED_PYTHON:2:1}.${DESIRED_PYTHON:3:1}"

# TODO do we need this?
# Reinitialize path (see man page for path_helper(8))
eval `/usr/libexec/path_helper -s`

# Building
###############################################################################
export BUILD_PYTHONLESS=1
./wheel/build_wheel.sh "\\$desired_python" "\\$PYTORCH_BUILD_VERSION" "\\$PYTORCH_BUILD_NUMBER"

# Upload the wheel
if [[ "$UPLOAD_PACKAGE" == true ]]; then
  pushd ./wheel
  ./upload.sh
  popd
fi

# Update html file
# TODO this should be moved to its own job
# ./update_s3_html.sh
'''
    }
  }
} // macLibtorchBuildEnvironments.each


//////////////////////////////////////////////////////////////////////////////
// Docker conda docker
//////////////////////////////////////////////////////////////////////////////
Images.dockerCondaBuildEnvironments.each {
  def buildEnvironment = it

  job("${uploadCondaBasePath}/${buildEnvironment}-build-upload") {
    JobUtil.common(delegate, 'docker && gpu')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.PYTORCH_REPO(delegate)
      ParametersUtil.PYTORCH_BRANCH(delegate)
      ParametersUtil.TORCH_CONDA_BUILD_FOLDER(delegate, 'pytorch-nightly')
      ParametersUtil.CMAKE_ARGS(delegate)
      ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
      ParametersUtil.RUN_TEST_PARAMS(delegate)
      ParametersUtil.UPLOAD_PACKAGE(delegate, false)
      ParametersUtil.PYTORCH_BUILD_VERSION(delegate, '0.5.0.dev20180913')
      ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '1')
      ParametersUtil.DEBUG(delegate, false)
    }

    wrappers {
      credentialsBinding {
        // This is needed so that Jenkins knows to hide these strings in all the console outputs
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    }

    steps {
      GitUtil.mergeStep(delegate)

      // Determine dockerfile, cpu builds on Dockerfile-cuda80
      def desired_cuda = 'cpu'
      if (buildEnvironment.contains('cuda')) {
        def cudaVer = buildEnvironment =~ /cuda(\d\d)/
        desired_cuda = cudaVer[0][1]
      }

      // Determine which python version to build
      def condaVersion = buildEnvironment =~ /^conda(\d.\d)/

      // Populate environment
      environmentVariables {
        env('BUILD_ENVIRONMENT', "${buildEnvironment}",)
        env('DESIRED_PYTHON', condaVersion[0][1])
        env('DESIRED_CUDA', desired_cuda)
      }

      DockerUtil.shell context: delegate,
              image: "soumith/conda-cuda:latest",
              cudaVersion: 'native',
              workspaceSource: "docker",
              usePipDockers: "true",
              script: '''
set -ex

# Jenkins passes DEBUG as a string, change this to what the script expects
if [[ "$DEBUG" == 'true' ]]; then
  export DEBUG=1
else
  unset DEBUG
fi

# Note. No pytorch repo is needed. conda-build/meta.yaml references it itself

# Build the conda packages
pushd /remote/conda
./build_pytorch.sh $DESIRED_CUDA $PYTORCH_BUILD_VERSION $PYTORCH_BUILD_NUMBER
'''
    } // steps
  }
} // dockerCondaBuildEnvironments

//////////////////////////////////////////////////////////////////////////////
// Docker pip
//////////////////////////////////////////////////////////////////////////////
Images.dockerPipBuildEnvironments.each {
  def buildEnvironment = it

  job("${uploadPipBasePath}/${buildEnvironment}-build-upload") {
    JobUtil.common(delegate, 'docker && gpu')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.PYTORCH_REPO(delegate)
      ParametersUtil.PYTORCH_BRANCH(delegate)
      ParametersUtil.CMAKE_ARGS(delegate)
      ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
      ParametersUtil.RUN_TEST_PARAMS(delegate)
      ParametersUtil.TORCH_PACKAGE_NAME(delegate, 'torch_nightly')
      ParametersUtil.UPLOAD_PACKAGE(delegate, false)
      ParametersUtil.PIP_UPLOAD_FOLDER(delegate, 'nightly/')
      ParametersUtil.PYTORCH_BUILD_VERSION(delegate, '0.5.0.dev20180913')
      ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '1')
      ParametersUtil.OVERRIDE_PACKAGE_VERSION(delegate, '')
      ParametersUtil.DEBUG(delegate, false)
    }

    wrappers {
      credentialsBinding {
        usernamePassword('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'PIP_S3_CREDENTIALS')
        // This is needed so that Jenkins knows to hide these strings in all the console outputs
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    }

    steps {
      GitUtil.mergeStep(delegate)

      // Determine dockerfile, cpu builds on Dockerfile-cuda80
      def cudaNoDot = '80'
      if (buildEnvironment.contains('cuda')) {
        def cudaVer = buildEnvironment =~ /cuda(\d\d)/
        cudaNoDot = cudaVer[0][1]
      }

      // Determine which python version to build
      def pyVersion = buildEnvironment =~ /(cp\d\d-cp\d\dmu?)/

      // Populate environment
      environmentVariables {
        env('BUILD_ENVIRONMENT', "${buildEnvironment}",)
        env('DESIRED_PYTHON', pyVersion[0][1])
        env('CUDA_NO_DOT', cudaNoDot)
      }

      DockerUtil.shell context: delegate,
              image: "soumith/manylinux-cuda${cudaNoDot}:latest",
              cudaVersion: 'native',
              workspaceSource: "docker",
              usePipDockers: "true",
              script: '''
set -ex

# Parameter checking
###############################################################################
if [[ -z "$AWS_ACCESS_KEY_ID" ]]; then
  echo "Caffe2 Pypi credentials are not propogated correctly."
  exit 1
fi

# Jenkins passes DEBUG as a string, change this to what the script expects
if [[ "$DEBUG" == 'true' ]]; then
  export DEBUG=1
else
  unset DEBUG
fi

# Pip converts all - to _  #TODO move this to build_common.sh
if [[ $TORCH_PACKAGE_NAME == *-* ]]; then
  export TORCH_PACKAGE_NAME="$(echo $TORCH_PACKAGE_NAME | tr '-' '_')"
  echo "WARNING:"
  echo "Pip will convert all dashes '-' to underscores '_' so we will actually"
  echo "use $TORCH_PACKAGE_NAME as the package name"
fi

# Install mkldnn
# TODO this is expensive and should be moved into the Docker images themselves
pushd /
#./remote/install_mkldnn.sh
popd

# Building
###############################################################################
# Clone the Pytorch branch into /pytorch, where the script below expects it
# TODO error out if the branch doesn't exist, as that's probably a user error
git clone "https://github.com/$PYTORCH_REPO/pytorch.git" /pytorch
pushd /pytorch
git checkout "$PYTORCH_BRANCH"
popd

# Build the pip packages, and define some variables used to upload the wheel
if [[ "$BUILD_ENVIRONMENT" == *cuda* ]]; then
  /remote/manywheel/build.sh
  wheelhouse_dir="/remote/wheelhouse$CUDA_NO_DOT"
  libtorch_house_dir="/remote/libtorch_house$CUDA_NO_DOT"
  s3_dir="cu$CUDA_NO_DOT"
else
  /remote/manywheel/build_cpu.sh
  wheelhouse_dir="/remote/wheelhousecpu"
  libtorch_house_dir="/remote/libtorch_housecpu"
  s3_dir="cpu"
fi

# Upload pip packages to s3, as they're too big for PyPI
if [[ $UPLOAD_PACKAGE == true ]]; then
  yes | /opt/python/cp27-cp27m/bin/pip install awscli==1.6.6

  # Upload wheel
  pushd /remote
  PATH=/opt/python/cp27-cp27m/bin/:$PATH CUDA_VERSIONS=$s3_dir /remote/manywheel/upload.sh
  popd

  # Update html file
  # TODO this should be moved to its own job
  #/remote/update_s3_html.sh
fi

# Print sizes of all wheels
echo "Succesfully built wheels of size:"
if ls /remote/wheelhouse*/torch*.whl >/dev/null 2>&1; then
  du -h /remote/wheelhouse*/torch*.whl
fi
'''
    } // steps
  }
} // dockerPipBuildEnvironments

//////////////////////////////////////////////////////////////////////////////
// Docker libtorch
//////////////////////////////////////////////////////////////////////////////
Images.dockerLibtorchBuildEnvironments.each {
  def buildEnvironment = it

  job("${uploadLibtorchBasePath}/${buildEnvironment}-build-upload") {
    JobUtil.common(delegate, 'docker && gpu')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.PYTORCH_REPO(delegate)
      ParametersUtil.PYTORCH_BRANCH(delegate)
      ParametersUtil.CMAKE_ARGS(delegate)
      ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
      ParametersUtil.RUN_TEST_PARAMS(delegate)
      ParametersUtil.TORCH_PACKAGE_NAME(delegate, 'torch_nightly')
      ParametersUtil.UPLOAD_PACKAGE(delegate, false)
      ParametersUtil.PIP_UPLOAD_FOLDER(delegate, 'nightly/')
      ParametersUtil.PYTORCH_BUILD_VERSION(delegate, '0.5.0.dev20180913')
      ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '1')
      ParametersUtil.OVERRIDE_PACKAGE_VERSION(delegate, '')
      ParametersUtil.DEBUG(delegate, false)
    }

    wrappers {
      credentialsBinding {
        usernamePassword('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'PIP_S3_CREDENTIALS')
        // This is needed so that Jenkins knows to hide these strings in all the console outputs
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    }

    steps {
      GitUtil.mergeStep(delegate)

      // Determine dockerfile, cpu builds on Dockerfile-cuda80
      def cudaNoDot = '80'
      if (buildEnvironment.contains('cuda')) {
        def cudaVer = buildEnvironment =~ /cuda(\d\d)/
        cudaNoDot = cudaVer[0][1]
      }

      // Determine which python version to build
      def pyVersion = buildEnvironment =~ /(cp\d\d-cp\d\dmu?)/

      // Populate environment
      environmentVariables {
        env('BUILD_ENVIRONMENT', "${buildEnvironment}",)
        env('DESIRED_PYTHON', pyVersion[0][1])
        env('CUDA_NO_DOT', cudaNoDot)
      }

      DockerUtil.shell context: delegate,
              image: "soumith/manylinux-cuda${cudaNoDot}:latest",
              cudaVersion: 'native',
              workspaceSource: "docker",
              usePipDockers: "true",
              script: '''
set -ex

# Parameter checking
###############################################################################
if [[ -z "$AWS_ACCESS_KEY_ID" ]]; then
  echo "Caffe2 Pypi credentials are not propogated correctly."
  exit 1
fi

# Jenkins passes DEBUG as a string, change this to what the script expects
if [[ "$DEBUG" == 'true' ]]; then
  export DEBUG=1
else
  unset DEBUG
fi

# Building
###############################################################################
# Clone the Pytorch branch into /pytorch, where the script below expects it
# TODO error out if the branch doesn't exist, as that's probably a user error
git clone "https://github.com/$PYTORCH_REPO/pytorch.git" /pytorch
pushd /pytorch
git checkout "$PYTORCH_BRANCH"
popd

export BUILD_PYTHONLESS=1

# Build the pip packages, and define some variables used to upload the wheel
if [[ "$BUILD_ENVIRONMENT" == *cuda* ]]; then
  /remote/manywheel/build.sh
  wheelhouse_dir="/remote/wheelhouse$CUDA_NO_DOT"
  libtorch_house_dir="/remote/libtorch_house$CUDA_NO_DOT"
  s3_dir="cu$CUDA_NO_DOT"
else
  /remote/manywheel/build_cpu.sh
  wheelhouse_dir="/remote/wheelhousecpu"
  libtorch_house_dir="/remote/libtorch_housecpu"
  s3_dir="cpu"
fi

if [[ $UPLOAD_PACKAGE == true ]]; then
  yes | /opt/python/cp27-cp27m/bin/pip install awscli==1.6.6

  # Upload libtorch
  echo "Uploading all of: $(ls $libtorch_house_dir) to: s3://pytorch/libtorch/${PIP_UPLOAD_FOLDER}${s3_dir}/"
  ls "$libtorch_house_dir/" | xargs -I {} /opt/python/cp27-cp27m/bin/aws s3 cp $libtorch_house_dir/{} "s3://pytorch/libtorch/${PIP_UPLOAD_FOLDER}${s3_dir}/" --acl public-read
fi

# Print sizes of all libtorch packages
echo "Succesfully built libtorchs of size:"
if ls /remote/libtorch_house*/libtorch*.zip >/dev/null 2>&1; then
  du -h /remote/libtorch_house*/libtorch*.zip
fi
'''
    } // steps
  }
} // dockerPipBuildEnvironments

//////////////////////////////////////////////////////////////////////////////
// Nightly upload jobs - just trigger the jobs above in bulk
//////////////////////////////////////////////////////////////////////////////
multiJob("nightly-pip-package-upload") {
  JobUtil.commonTrigger(delegate)
  JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')
  parameters {
    ParametersUtil.GIT_COMMIT(delegate)
    ParametersUtil.GIT_MERGE_TARGET(delegate)
    ParametersUtil.PYTORCH_REPO(delegate)
    ParametersUtil.PYTORCH_BRANCH(delegate)
    ParametersUtil.CMAKE_ARGS(delegate)
    ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
    ParametersUtil.RUN_TEST_PARAMS(delegate)
    ParametersUtil.TORCH_PACKAGE_NAME(delegate, 'torch_nightly')
    ParametersUtil.UPLOAD_PACKAGE(delegate, false)
    ParametersUtil.PIP_UPLOAD_FOLDER(delegate, 'nightly/')
    ParametersUtil.PYTORCH_BUILD_VERSION(delegate, '0.5.0.dev20180913')
    ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '1')
    ParametersUtil.OVERRIDE_PACKAGE_VERSION(delegate, '')
    ParametersUtil.DEBUG(delegate, false)
  }
  triggers {
    cron('@daily')
  }

  steps {
    def gitPropertiesFile = './git.properties'
    GitUtil.mergeStep(delegate)
    GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

    phase("Build") {
      def definePhaseJob = { name ->
        phaseJob("${uploadPipBasePath}/${name}-build-upload") {
          parameters {
            currentBuild()
            propertiesFile(gitPropertiesFile)
          }
        }
      }

      Images.macPipBuildEnvironments.each {
        definePhaseJob(it)
      }

      assert 'pip-cp27-cp27m-cuda90-linux' in Images.dockerPipBuildEnvironments
      Images.dockerPipBuildEnvironments.each {
        definePhaseJob(it)
      }
    } // phase(Build)

    publishers {
      groovyPostBuild {
        script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript('hellemn@fb.com'))
      }
    }
  } // steps
} // nightly pip


// nightly conda
multiJob("nightly-conda-package-upload") {
  JobUtil.commonTrigger(delegate)
  JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')
  parameters {
    ParametersUtil.GIT_COMMIT(delegate)
    ParametersUtil.GIT_MERGE_TARGET(delegate)
    ParametersUtil.PYTORCH_REPO(delegate)
    ParametersUtil.PYTORCH_BRANCH(delegate)
    ParametersUtil.TORCH_CONDA_BUILD_FOLDER(delegate, 'pytorch-nightly')
    ParametersUtil.CMAKE_ARGS(delegate)
    ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
    ParametersUtil.RUN_TEST_PARAMS(delegate)
    ParametersUtil.UPLOAD_PACKAGE(delegate, false)
    ParametersUtil.PYTORCH_BUILD_VERSION(delegate, '0.5.0.dev20180913')
    ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '1')
    ParametersUtil.DEBUG(delegate, false)
  }
  triggers {
    cron('@daily')
  }

  steps {
    def gitPropertiesFile = './git.properties'
    GitUtil.mergeStep(delegate)
    GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

    phase("Build") {
      def definePhaseJob = { name ->
        phaseJob("${uploadCondaBasePath}/${name}-build-upload") {
          parameters {
            currentBuild()
            propertiesFile(gitPropertiesFile)
          }
        }
      }

      Images.macCondaBuildEnvironments.each {
        definePhaseJob(it)
      }

      assert 'conda2.7-cuda80-linux' in Images.dockerCondaBuildEnvironments
      Images.dockerCondaBuildEnvironments.each {
        definePhaseJob(it)
      }
    } // phase(Build)

    publishers {
      groovyPostBuild {
        script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript('hellemn@fb.com'))
      }
    }
  } // steps
} // nightly conda

// nightly uploaded
multiJob("nightlies-finished") {
  JobUtil.commonTrigger(delegate)
  parameters {
    ParametersUtil.DATE(delegate)
  }

  // These run at 9am everday, which should give 3 hours for each job to finish
  // in the cluster (jobs start at 1am and the default timeout is less than 3
  // hours).
  // By 9am, I meant 9am PST, because the nightly jobs run at 0:00 am PST. But
  // the jenkins machines appear to run in GMT so we add 7 here
  triggers {
    cron('0 16 * * *')
  }

  steps {

    phase("Build") {
      def definePhaseJob = { name ->
        phaseJob("${nightliesUploadedBasePath}/${name}-uploaded") {
          parameters {
            currentBuild()
          }
        }
      }

      Images.allNightlyBuildEnvironments.each {
        definePhaseJob(it)
      }
    } // phase(Build)

    publishers {
      groovyPostBuild {
        script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript('hellemn@fb.com'))
      }
    }
  } // steps
} // nightly conda
