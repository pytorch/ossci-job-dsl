import ossci.DockerUtil
import ossci.EmailUtil
import ossci.GitUtil
import ossci.JobUtil
import ossci.MacOSUtil
import ossci.ParametersUtil
import ossci.PhaseJobUtil
import ossci.caffe2.Images
import ossci.caffe2.DockerVersion


def caffe2OnlyCondaUploadBasePath = 'caffe2-conda-packages'
def uploadCondaBasePath = 'conda-packages'
def uploadPipBasePath = 'pip-packages'
def uploadLibtorchBasePath = "libtorch-packages"
folder(caffe2OnlyCondaUploadBasePath) {
  description 'Jobs for nightly uploads of Caffe2 packages'
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
// Mac Caffe2 Conda
//////////////////////////////////////////////////////////////////////////////
Images.macCaffe2CondaBuildEnvironments.each {
  def buildEnvironment = it
  job("${caffe2OnlyCondaUploadBasePath}/${buildEnvironment}-build-upload") {
  JobUtil.common(delegate, 'osx')
  JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/pytorch')

  parameters {
    ParametersUtil.GIT_COMMIT(delegate)
    ParametersUtil.GIT_MERGE_TARGET(delegate)
    ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
    ParametersUtil.UPLOAD_PACKAGE(delegate)
    ParametersUtil.TORCH_PACKAGE_NAME(delegate)
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

    // Conda still has some 'integrated' builds
    environmentVariables {
      if (Images.integratedEnvironments.contains(buildEnvironment)) {
        env('INTEGRATED', 1)
      }
    }

    // Read the python or conda version
    def condaVersion = buildEnvironment =~ /^conda([0-9])/
    environmentVariables {
      env('ANACONDA_VERSION', condaVersion[0][1])
    }

    MacOSUtil.sandboxShell delegate, '''
set -ex

# Need to checkout fetch PRs for onnxbot tracking PRs
git submodule update --init third_party/onnx || true
cd third_party/onnx && git fetch --tags --progress origin +refs/pull/*:refs/remotes/origin/pr/* && cd -

# Reinitialize submodules
git submodule update --init --recursive

# Reinitialize path (see man page for path_helper(8))
eval `/usr/libexec/path_helper -s`

# Fix for xcode-select in jenkins
export DEVELOPER_DIR=/Applications/Xcode9.app/Contents/Developer

# Install Anaconda if we need to
rm -rf ${TMPDIR}/anaconda
curl -o ${TMPDIR}/anaconda.sh "https://repo.continuum.io/archive/Anaconda${ANACONDA_VERSION}-5.0.1-MacOSX-x86_64.sh"
/bin/bash ${TMPDIR}/anaconda.sh -b -p ${TMPDIR}/anaconda
rm -f ${TMPDIR}/anaconda.sh
export PATH="${TMPDIR}/anaconda/bin:${PATH}"
source ${TMPDIR}/anaconda/bin/activate

# Build
scripts/build_anaconda.sh --name "$TORCH_PACKAGE_NAME"
'''
    }
  }
} // macCaffe2CondaBuildEnvironments.each

//////////////////////////////////////////////////////////////////////////////
// Mac Conda
//////////////////////////////////////////////////////////////////////////////
Images.macCondaBuildEnvironments.each {
  def buildEnvironment = it
  job("${uploadCondaBasePath}/${buildEnvironment}-build-upload") {
  JobUtil.common(delegate, 'osx')
  JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.GITHUB_ORG(delegate)
      ParametersUtil.PYTORCH_BRANCH(delegate)
      ParametersUtil.TORCH_CONDA_BUILD_FOLDER(delegate, 'pytorch-nightly')
      ParametersUtil.CMAKE_ARGS(delegate)
      ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
      ParametersUtil.RUN_TEST_PARAMS(delegate)
      ParametersUtil.UPLOAD_PACKAGE(delegate, false)
      ParametersUtil.PYTORCH_BUILD_VERSION(delegate, 'nightly')
      ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '0')
      ParametersUtil.FULL_CAFFE2(delegate, false)
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

# Jenkins passes FULL_CAFFE2 as a string, change this to what the script expects
if [[ "$FULL_CAFFE2" == 'true' ]]; then
  export FULL_CAFFE2=1
else
  unset FULL_CAFFE2
fi
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

# Build
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
    ParametersUtil.GITHUB_ORG(delegate, 'pytorch')
    ParametersUtil.PYTORCH_BRANCH(delegate, 'master')
    ParametersUtil.CMAKE_ARGS(delegate)
    ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
    ParametersUtil.RUN_TEST_PARAMS(delegate)
    ParametersUtil.TORCH_PACKAGE_NAME(delegate, 'torch_nightly')
    ParametersUtil.UPLOAD_PACKAGE(delegate, false)
    ParametersUtil.PIP_UPLOAD_FOLDER(delegate, 'nightly/')
    ParametersUtil.USE_DATE_AS_VERSION(delegate, true)
    ParametersUtil.VERSION_POSTFIX(delegate, '.dev01')
    ParametersUtil.OVERRIDE_PACKAGE_VERSION(delegate, '')
    ParametersUtil.FULL_CAFFE2(delegate, false)
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

# Jenkins passes FULL_CAFFE2 as a string, change this to what the script expects
if [[ "$FULL_CAFFE2" == 'true' ]]; then
  export FULL_CAFFE2=1
else
  unset FULL_CAFFE2
fi
if [[ "$DEBUG" == 'true' ]]; then
  export DEBUG=1
else
  unset DEBUG
fi
# TODO fix images.groovy instead of this ugly hack
DESIRED_PYTHON="${DESIRED_PYTHON:2:1}.${DESIRED_PYTHON:3:1}"

# Version: setup.py uses $PYTORCH_BUILD_VERSION.post$PYTORCH_BUILD_NUMBER
export PYTORCH_BUILD_NUMBER=0
if [[ -n "$OVERRIDE_PACKAGE_VERSION" ]]; then
  echo 'Using override-version'
  export PYTORCH_BUILD_VERSION="$OVERRIDE_PACKAGE_VERSION"
elif [[ "$USE_DATE_AS_VERSION" == true ]]; then
  echo 'Using the current date + VERSION_POSTFIX'
  export PYTORCH_BUILD_VERSION="$(date +%Y.%m.%d)${VERSION_POSTFIX}"
else
  echo "WARNING:"
  echo "No version parameters were set, so this will use whatever the default"
  echo "version logic within setup.py is."
fi

# TODO do we need this?
# Reinitialize path (see man page for path_helper(8))
eval `/usr/libexec/path_helper -s`

# Building
###############################################################################
./wheel/build_wheel.sh "$DESIRED_PYTHON" "$PYTORCH_BUILD_VERSION" 0
'''
    }
  }
} // macCondaBuildEnvironments.each


//////////////////////////////////////////////////////////////////////////////
// Docker Caffe2 conda
//////////////////////////////////////////////////////////////////////////////
Images.dockerCaffe2CondaBuildEnvironments.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it
  def dockerBaseImage = Images.baseImageOf[(buildEnvironment)]
  def dockerImage = { tag ->
    // If image tag contains '/', we need to replace it with '-'
    return "308535385114.dkr.ecr.us-east-1.amazonaws.com/caffe2/${dockerBaseImage}:${tag}"
  }

  job("${caffe2OnlyCondaUploadBasePath}/${buildEnvironment}-build-upload") {
    JobUtil.common(delegate, buildEnvironment.contains('cuda') ? 'docker && gpu' : 'docker && cpu')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/pytorch')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
      ParametersUtil.UPLOAD_PACKAGE(delegate)
      ParametersUtil.TORCH_PACKAGE_NAME(delegate)
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
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
      environmentVariables {
        env('BUILD_ENVIRONMENT', "${buildEnvironment}",)
        if (Images.integratedEnvironments.contains(buildEnvironment)) {
          env('INTEGRATED', 1)
        }
        if (buildEnvironment.contains('slim')) {
          env('SLIM', 1)
        }
      }

      def cudaVersion = ''
      if (buildEnvironment.contains('cuda')) {
        cudaVersion = 'native';
        // Populate CUDA and cuDNN versions in case we're building pytorch too,
        // which expects these variables to be set
        def cudaVer = buildEnvironment =~ /cuda(\d.\d)/
        def cudnnVer = buildEnvironment =~ /cudnn(\d)/
        environmentVariables {
          env('CUDA_VERSION', cudaVer[0][1])
          env('CUDNN_VERSION', cudnnVer[0][1])
        }
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              cudaVersion: cudaVersion,
              // TODO: use 'docker'. Make sure you copy out the test result XML
              // to the right place
              workspaceSource: "host-mount",
              script: '''
set -ex
if [[ -z "$ANACONDA_USERNAME" ]]; then
  echo "Caffe2 Anaconda credentials are not propogated correctly."
  exit 1
fi
git submodule update --init --recursive
if [[ -n $UPLOAD_PACKAGE ]]; then
  upload_to_conda="--upload"
fi
if [[ -n $TORCH_PACKAGE_NAME ]]; then
  package_name="--name $TORCH_PACKAGE_NAME"
fi
if [[ -n $SLIM ]]; then
  slim="--slim"
fi

# All conda build logic should be in scripts/build_anaconda.sh
# TODO move lang vars into Dockerfile
PATH=/opt/conda/bin:$PATH LANG=C.UTF-8 LC_ALL=C.UTF-8 ./scripts/build_anaconda.sh $upload_to_conda $package_name $slim
'''
    }
  }
}


//////////////////////////////////////////////////////////////////////////////
// Docker conda
//////////////////////////////////////////////////////////////////////////////
Images.dockerCondaBuildEnvironments.each {
  def buildEnvironment = it

  job("${uploadCondaBasePath}/${buildEnvironment}-build-upload") {
    JobUtil.common(delegate, 'docker && gpu')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.GITHUB_ORG(delegate)
      ParametersUtil.PYTORCH_BRANCH(delegate)
      ParametersUtil.TORCH_CONDA_BUILD_FOLDER(delegate, 'pytorch-nightly')
      ParametersUtil.CMAKE_ARGS(delegate)
      ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
      ParametersUtil.RUN_TEST_PARAMS(delegate)
      ParametersUtil.UPLOAD_PACKAGE(delegate, false)
      ParametersUtil.PYTORCH_BUILD_VERSION(delegate, 'nightly')
      ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '0')
      ParametersUtil.FULL_CAFFE2(delegate, false)
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

# Jenkins passes FULL_CAFFE2 as a string, change this to what the script expects
if [[ "$FULL_CAFFE2" == 'true' ]]; then
  export FULL_CAFFE2=1
else
  unset FULL_CAFFE2
fi
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
      ParametersUtil.GITHUB_ORG(delegate)
      ParametersUtil.PYTORCH_BRANCH(delegate)
      ParametersUtil.CMAKE_ARGS(delegate)
      ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
      ParametersUtil.RUN_TEST_PARAMS(delegate)
      ParametersUtil.TORCH_PACKAGE_NAME(delegate, 'torch_nightly')
      ParametersUtil.UPLOAD_PACKAGE(delegate, false)
      ParametersUtil.PIP_UPLOAD_FOLDER(delegate, 'nightly/')
      ParametersUtil.USE_DATE_AS_VERSION(delegate, true)
      ParametersUtil.VERSION_POSTFIX(delegate, '.dev01')
      ParametersUtil.OVERRIDE_PACKAGE_VERSION(delegate, '')
      ParametersUtil.FULL_CAFFE2(delegate, false)
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

# Jenkins passes FULL_CAFFE2 as a string, change this to what the script expects
if [[ "$FULL_CAFFE2" == 'true' ]]; then
  export FULL_CAFFE2=1
else
  unset FULL_CAFFE2
fi
if [[ "$DEBUG" == 'true' ]]; then
  export DEBUG=1
else
  unset DEBUG
fi

# Version: setup.py uses $PYTORCH_BUILD_VERSION.post$PYTORCH_BUILD_NUMBER
export PYTORCH_BUILD_NUMBER=0
if [[ -n "$OVERRIDE_PACKAGE_VERSION" ]]; then
  echo 'Using override-version'
  export PYTORCH_BUILD_VERSION="$OVERRIDE_PACKAGE_VERSION"
elif [[ "$USE_DATE_AS_VERSION" == true ]]; then
  echo 'Using the current date + VERSION_POSTFIX'
  export PYTORCH_BUILD_VERSION="$(date +%Y.%m.%d)${VERSION_POSTFIX}"
else
  echo "WARNING:"
  echo "No version parameters were set, so this will use whatever the default"
  echo "version logic within setup.py is."
fi

# Pip converts all - to _  #TODO move this to build_common.sh
if [[ $TORCH_PACKAGE_NAME == *-* ]]; then
  export TORCH_PACKAGE_NAME="$(echo $TORCH_PACKAGE_NAME | tr '-' '_')"
  echo "WARNING:"
  echo "Pip will convert all dashes '-' to underscores '_' so we will actually"
  echo "use $TORCH_PACKAGE_NAME as the package name"
fi

# Building
###############################################################################
# Clone the Pytorch branch into /pytorch, where the script below expects it
# TODO error out if the branch doesn't exist, as that's probably a user error
git clone "https://github.com/$GITHUB_ORG/pytorch.git" /pytorch
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
      ParametersUtil.GITHUB_ORG(delegate)
      ParametersUtil.PYTORCH_BRANCH(delegate)
      ParametersUtil.CMAKE_ARGS(delegate)
      ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
      ParametersUtil.RUN_TEST_PARAMS(delegate)
      ParametersUtil.TORCH_PACKAGE_NAME(delegate, 'torch_nightly')
      ParametersUtil.UPLOAD_PACKAGE(delegate, false)
      ParametersUtil.PIP_UPLOAD_FOLDER(delegate, 'nightly/')
      ParametersUtil.USE_DATE_AS_VERSION(delegate, true)
      ParametersUtil.VERSION_POSTFIX(delegate, '.dev01')
      ParametersUtil.OVERRIDE_PACKAGE_VERSION(delegate, '')
      ParametersUtil.FULL_CAFFE2(delegate, false)
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

# Jenkins passes FULL_CAFFE2 as a string, change this to what the script expects
if [[ "$FULL_CAFFE2" == 'true' ]]; then
  export FULL_CAFFE2=1
else
  unset FULL_CAFFE2
fi
if [[ "$DEBUG" == 'true' ]]; then
  export DEBUG=1
else
  unset DEBUG
fi

# Version: setup.py uses $PYTORCH_BUILD_VERSION.post$PYTORCH_BUILD_NUMBER
export PYTORCH_BUILD_NUMBER=0
if [[ -n "$OVERRIDE_PACKAGE_VERSION" ]]; then
  echo 'Using override-version'
  export PYTORCH_BUILD_VERSION="$OVERRIDE_PACKAGE_VERSION"
elif [[ "$USE_DATE_AS_VERSION" == true ]]; then
  echo 'Using the current date + VERSION_POSTFIX'
  export PYTORCH_BUILD_VERSION="$(date +%Y.%m.%d)${VERSION_POSTFIX}"
else
  echo "WARNING:"
  echo "No version parameters were set, so this will use whatever the default"
  echo "version logic within setup.py is."
fi

# Building
###############################################################################
# Clone the Pytorch branch into /pytorch, where the script below expects it
# TODO error out if the branch doesn't exist, as that's probably a user error
git clone "https://github.com/$GITHUB_ORG/pytorch.git" /pytorch
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
multiJob("nightly-caffe2-conda-package-upload") {
  JobUtil.commonTrigger(delegate)
  JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/pytorch')
  parameters {
    ParametersUtil.GIT_COMMIT(delegate)
    ParametersUtil.GIT_MERGE_TARGET(delegate)
    ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    ParametersUtil.CMAKE_ARGS(delegate, '-DCUDA_ARCH_NAME=ALL')
    ParametersUtil.UPLOAD_PACKAGE(delegate, true)
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
        phaseJob("${caffe2OnlyCondaUploadBasePath}/${name}-build-upload") {
          parameters {
            currentBuild()
            propertiesFile(gitPropertiesFile)
          }
        }
      }

      Images.macCaffe2CondaBuildEnvironments.each {
        definePhaseJob(it)
      }

      assert 'conda2-ubuntu16.04' in Images.dockerCaffe2CondaBuildEnvironments
      Images.dockerCaffe2CondaBuildEnvironments.each {
        if (!it.contains('gcc4.8')) {
          definePhaseJob(it)
        }
      }
    } // phase(Build)

    publishers {
      groovyPostBuild {
        script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript('hellemn@fb.com'))
      }
    }
  } // steps
} // nightly conda

// Pips
multiJob("nightly-pip-package-upload") {
  JobUtil.commonTrigger(delegate)
  JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/builder')
  parameters {
    ParametersUtil.GIT_COMMIT(delegate)
    ParametersUtil.GIT_MERGE_TARGET(delegate)
    ParametersUtil.GITHUB_ORG(delegate)
    ParametersUtil.PYTORCH_BRANCH(delegate)
    ParametersUtil.CMAKE_ARGS(delegate)
    ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
    ParametersUtil.RUN_TEST_PARAMS(delegate)
    ParametersUtil.TORCH_PACKAGE_NAME(delegate, 'torch_nightly')
    ParametersUtil.UPLOAD_PACKAGE(delegate, true)
    ParametersUtil.PIP_UPLOAD_FOLDER(delegate, 'nightly/')
    ParametersUtil.USE_DATE_AS_VERSION(delegate, true)
    ParametersUtil.VERSION_POSTFIX(delegate, '.dev01')
    ParametersUtil.OVERRIDE_PACKAGE_VERSION(delegate, '')
    ParametersUtil.FULL_CAFFE2(delegate, false)
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
    ParametersUtil.GITHUB_ORG(delegate)
    ParametersUtil.PYTORCH_BRANCH(delegate)
    ParametersUtil.TORCH_CONDA_BUILD_FOLDER(delegate, 'pytorch-nightly')
    ParametersUtil.CMAKE_ARGS(delegate)
    ParametersUtil.EXTRA_CAFFE2_CMAKE_FLAGS(delegate)
    ParametersUtil.RUN_TEST_PARAMS(delegate)
    ParametersUtil.UPLOAD_PACKAGE(delegate, false)
    ParametersUtil.PYTORCH_BUILD_VERSION(delegate, 'nightly')
    ParametersUtil.PYTORCH_BUILD_NUMBER(delegate, '0')
    ParametersUtil.FULL_CAFFE2(delegate, false)
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
