import ossci.DockerUtil
import ossci.EmailUtil
import ossci.GitUtil
import ossci.JobUtil
import ossci.MacOSUtil
import ossci.ParametersUtil
import ossci.PhaseJobUtil
import ossci.caffe2.Images
import ossci.caffe2.DockerVersion


def nightliesUploadedBasePath = 'nightlies-smoke-tests'
folder(nightliesUploadedBasePath) {
  description 'Jobs to see if the nightly packages were uploaded for the day'
}

//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////
// README
//
// jobs just check that the nightly jobs uploaded for the day, by downloading
// them from the public repos and checking the date in the version.  These run
// at 9am everday, which should give 3 hours for each job to finish in the
// cluster (jobs start at 1am and the default timeout is less than 3 hours).
// 
//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////


//////////////////////////////////////////////////////////////////////////////
// Uploaded jobs
//////////////////////////////////////////////////////////////////////////////
Images.allNightlyBuildEnvironments.each {
  def buildEnvironment = it

  job("${nightliesUploadedBasePath}/${buildEnvironment}") {
    def buildForMac = buildEnvironment.contains('macos')
    def packageType = buildEnvironment.contains('conda') ? 'conda' : 'pip'
    def buildForCpu = buildEnvironment.contains('_cpu')

    // Delegate to either a Mac or a Linux machine
    if (buildForMac) {
      JobUtil.common(delegate, 'osx')
    } else {
      if (!buildForCpu) {
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
      // Set DESIRED_CUDA to 'cpu' or 'cu##'
      def desiredCuda = 'cpu'
      def cudaVersion = ''
      if (!buildForCpu) {
        def cudaVer = buildEnvironment =~ /.*_(cu\d\d)/
        desiredCuda = cudaVer[0][1]
        cudaVersion = 'native'
      }

      // Set Python to 'cp##-cp##mu?' or '#.#'
      def pyMatch = buildEnvironment =~ /.*_(\d.\dm?u?)_.*/
      def pyVersion = pyMatch[0][1]

      // Set Docker image
      def dockerImage = ''
      if (!buildForMac) {
        if (buildEnvironment.contains('conda')) {
          dockerImage = 'soumith/conda-cuda'
        } else if (buildForCpu) {
          dockerImage = 'soumith/manylinux-cuda80'
        } else {
          dockerImage = 'soumith/manylinux-cuda' + desiredCuda.substring(2)
        }
        dockerImage = dockerImage + ':latest'
      }

      // Set the script before calling into it so that we don't have to copy it
      // across jobs
      def uploaded_job_script = '''
set -ex

# Use today's date if none is given
if [[ "$DATE" == 'today' ]]; then
    DATE="$(date +%Y%m%d)"
fi

# DESIRED_PYTHON is in format 2.7m?u?
# DESIRED_CUDA is in format cu80 (or 'cpu')

# Generate M.m formats for CUDA and Python versions
cuda_dot="${DESIRED_CUDA:2:1}.${DESIRED_CUDA:3:1}"
py_dot="${DESIRED_PYTHON:0:3}"

# Generate "long" python versions cp27-cp27mu
py_long="cp${DESIRED_PYTHON:0:1}${DESIRED_PYTHON:2:1}-cp${DESIRED_PYTHON:0:1}${DESIRED_PYTHON:2}"

# Determine package name
if [[ "$PACKAGE_TYPE" == *wheel ]]; then
  package_name='torch-nightly'
elif [[ "$DESIRED_CUDA" == 'cpu' && "$(uname)" != 'Darwin' ]]; then
  package_name='pytorch-nightly-cpu'
else
  package_name='pytorch-nightly'
fi
if [[ "$(uname)" == 'Darwin' ]]; then
  package_name_and_version="\\${package_name}==\\${NIGHTLIES_DATE_PREAMBLE}\\${DATE}"
else
  package_name_and_version="${package_name}==${NIGHTLIES_DATE_PREAMBLE}${DATE}"
fi

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
  conda install -yq future numpy protobuf six
else
  export PATH=/opt/python/${py_long}/bin:$PATH
  pip install future numpy protobuf six
fi

# Switch to the desired CUDA if using the conda-cuda Docker image
if [[ "$PACKAGE_TYPE" == 'conda' ]]; then
  rm -rf /usr/local/cuda || true
  if [[ "$DESIRED_CUDA" != 'cpu' ]]; then
    ln -s "/usr/local/cuda-${cuda_dot}" /usr/local/cuda
    export CUDA_VERSION=$(ls /usr/local/cuda/lib64/libcudart.so.*|sort|tac | head -1 | rev | cut -d"." -f -3 | rev)
    export CUDNN_VERSION=$(ls /usr/local/cuda/lib64/libcudnn.so.*|sort|tac | head -1 | rev | cut -d"." -f -3 | rev)
  fi
fi

# Print some debugging info
python --version
which python
if [[ "$PACKAGE_TYPE" == 'conda' ]]; then
  if [[ "$(uname)" == 'Darwin' ]]; then
    conda search -c pytorch "\\$package_name"
  else
    conda search -c pytorch "$package_name"
  fi
else
  curl "https://download.pytorch.org/whl/nightly/$DESIRED_CUDA/torch_nightly.html"
fi

# Install the package for the requested date
if [[ "$PACKAGE_TYPE" == 'conda' ]]; then
  if [[ "$(uname)" == 'Darwin' ]]; then
    conda install -yq -c pytorch "\\$package_name_and_version"
  elif [[ "$DESIRED_CUDA" == 'cpu' || "$DESIRED_CUDA" == 'cu90' ]]; then
    conda install -yq -c pytorch "$package_name_and_version"
  else
    conda install -yq -c pytorch "cuda${DESIRED_CUDA:2:2}" "$package_name_and_version"
  fi
else
  if [[ "$(uname)" == 'Darwin' ]]; then
    pip install "\\$package_name_and_version" \
        -f "https://download.pytorch.org/whl/nightly/$DESIRED_CUDA/torch_nightly.html" \
        --no-cache-dir \
        --no-index \
        -v
  else
    pip install "$package_name_and_version" \
        -f "https://download.pytorch.org/whl/nightly/$DESIRED_CUDA/torch_nightly.html" \
        --no-cache-dir \
        --no-index \
        -v
  fi
fi

# Check that conda didn't do something dumb
if [[ "$PACKAGE_TYPE" == 'conda' ]]; then
  # Check that conda didn't change the Python version out from under us
  if [[ "$(uname)" == 'Darwin' ]]; then
    if [[ -z "\\$(python --version 2>&1 | grep -o \\$py_dot)" ]]; then
      echo "The Python version has changed to \\$(python --version)"
      echo "Probably the package for the version we want does not exist"
      echo '(conda will change the Python version even if it was explicitly declared)'
      exit 1
    fi
  else
    if [[ -z "$(python --version 2>&1 | grep -o $py_dot)" ]]; then
      echo "The Python version has changed to $(python --version)"
      echo "Probably the package for the version we want does not exist"
      echo '(conda will change the Python version even if it was explicitly declared)'
      exit 1
    fi
  fi

  # Check that the CUDA feature is working
  if [[ "$DESIRED_CUDA" == 'cpu' ]]; then
    if [[ "$(uname)" == 'Darwin' ]]; then
      if [[ -n "\\$(conda list torch | grep -o cuda)" ]]; then
        echo "The installed package is built for CUDA:: \\$(conda list torch)"
        exit 1
      fi
    else
      if [[ -n "$(conda list torch | grep -o cuda)" ]]; then
        echo "The installed package is built for CUDA:: $(conda list torch)"
        exit 1
      fi
    fi
  elif [[ -z "$(conda list torch | grep -o cuda$cuda_dot)" ]]; then
    echo "The installed package doesn't seem to be built for CUDA $cuda_dot"
    echo "The full package is $(conda list torch)"
    exit 1
  fi
fi

# Quick smoke test that it works
echo "Smoke testing imports"
python -c 'import torch'
python -c 'from caffe2.python import core'

# Test that MKL is there
if [[ "$(uname)" == 'Darwin' && "$PACKAGE_TYPE" == wheel ]]; then
  echo 'Not checking for MKL on Darwin wheel packages'
else
  echo "Checking that MKL is available"
  python -c 'import torch; exit(0 if torch.backends.mkl.is_available() else 1)'
fi

# Test that CUDA builds are setup correctly
if [[ "$DESIRED_CUDA" != 'cpu' ]]; then
  # Test CUDA archs
  echo "Checking that CUDA archs are setup correctly"
  timeout 20 python -c 'import torch; torch.randn([3,5]).cuda()'

  # These have to run after CUDA is initialized
  echo "Checking that magma is available"
  python -c 'import torch; torch.rand(1).cuda(); exit(0 if torch.cuda.has_magma else 1)'

  echo "Checking that CuDNN is available"
  python -c 'import torch; exit(0 if torch.backends.cudnn.is_available() else 1)'
fi

# Check that OpenBlas is not linked to on Macs
if [[ "$(uname)" == 'Darwin' ]]; then
  echo "Checking the OpenBLAS is not linked to"
  all_dylibs=($(find "${TMPDIR}/anaconda/envs/test/lib/python${DESIRED_PYTHON}/site-packages/torch/" -name '*.dylib'))
  for dylib in "\\${all_dylibs[@]}"; do
    if [[ -n "\\$(otool -L \\$dylib | grep -i openblas)" ]]; then
      echo "Found openblas as a dependency of \\$dylib"
      echo "Full dependencies is: \\$(otool -L \\$dylib)"
      exit 1
    fi
  done
fi

# Echo the location of the logs
if [[ "$(uname)" == 'Darwin' ]]; then
  echo "The logfile for this run can be found at https://download.pytorch.org/nightly_logs/macos/\\${DATE:1:4}_\\${DATE:4:2}_\\${DATE:6:2}"/${PACKAGE_TYPE}_${DESIRED_PYTHON}_cpu.log"
else
  echo "The logfile for this run can be found at https://download.pytorch.org/nightly_logs/linux/${DATE:1:4}_${DATE:4:2}_${DATE:6:2}"/${PACKAGE_TYPE}_${DESIRED_PYTHON}_${DESIRED_CUDA}.log"
fi
'''

      environmentVariables {
        env('DESIRED_PYTHON', pyVersion)
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
} // allNightliesBuildEnvironments

//////////////////////////////////////////////////////////////////////////////
// Nightly upload jobs - just trigger the jobs above in bulk
//////////////////////////////////////////////////////////////////////////////
multiJob("nightlies-uploaded") {
  JobUtil.commonTrigger(delegate)
  parameters {
    ParametersUtil.DATE(delegate)
    ParametersUtil.NIGHTLIES_DATE_PREAMBLE(delegate)
  }

  // These run at 9am everday, which should give 3 hours for each job to finish
  // in the cluster (jobs start at 1am and the default timeout is less than 3
  // hours).
  // By 9am, I meant 9am PST, because the nightly jobs run at 0:00 am PST. But
  // the jenkins machines appear to run in GMT so we add 7 here
  triggers {
    cron('15 16 * * *')
  }

  steps {

    phase("Build") {
      def definePhaseJob = { name ->
        phaseJob("${nightliesUploadedBasePath}/${name}") {
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
