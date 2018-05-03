import ossci.DockerUtil
import ossci.JobUtil
import ossci.ParametersUtil
import ossci.PhaseJobUtil
import ossci.pytorch.Users
import ossci.caffe2.DockerImages
import ossci.caffe2.DockerVersion


def translateBasePath = 'translate'
def translateDockerBuildEnvironments = [
  'conda3-cuda9.0-cudnn7-ubuntu16.04',
  'conda3-cuda8.0-cudnn7-ubuntu16.04',
]

folder(translateBasePath) {
  description 'Jobs for translate'
}

// This is a temporary solution, and copied from caffe2.groovy
translateDockerBuildEnvironments.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  // Every build environment has its own Docker image
  def dockerImage = { tag ->
    return "308535385114.dkr.ecr.us-east-1.amazonaws.com/caffe2/${buildEnvironment}:${tag}"
  }

  // WARNING that both of these jobs produce the same outputs, and so will
  // probably overwrite each other if all run together.

  // Job to build translate from source, and also build Pytorch and Caffe2
  // from source. These builds are only temporary, as they should either
  //   1. be replaced by binary installs for much-faster build times or
  //   2. be integrated into the rest of CI so as to use the outputs of other
  //      Caffe2/pytorch builds
  job("${translateBasePath}/${buildEnvironment}-source") {
    JobUtil.common(delegate, 'docker && ((cpu && ccache) || cpu_ccache)')

    parameters {
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.CMAKE_ARGS(delegate)

      stringParam(
        'DOCKER_COMMIT_TAG',
        '${DOCKER_IMAGE_TAG}-adhoc-${BUILD_ID}',
        "Tag of the Docker image to commit and push upon completion " +
          "(${buildEnvironment}:DOCKER_COMMIT_TAG)",
      )
    }

    steps {

      environmentVariables {
        env('BUILD_ENVIRONMENT', "${buildEnvironment}")
      }

      def cudaVersion = buildEnvironment =~ /cuda(\d.\d)/
      environmentVariables {
        env('CUDA_VERSION', cudaVersion[0][1])
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              cudaVersion: 'native',
              commitImage: dockerImage('${DOCKER_COMMIT_TAG}'),
              // TODO: use 'host-copy'. Make sure you copy out the archived artifacts
              workspaceSource: "host-mount",
              script: '''
set -ex


# Ensure jenkins can write to the ccache root dir.
sudo chown jenkins:jenkins "${HOME}/.ccache"
sudo chown -R jenkins:jenkins '/opt/conda'
export PATH=/opt/conda/bin:$PATH

# Make ccache log to the workspace, so we can archive it after the build
mkdir -p build
ccache -o log_file=$PWD/build/ccache.log


git clone --recursive https://github.com/pytorch/pytorch.git && pushd pytorch


# Install pytorch
yes | conda install numpy pyyaml mkl mkl-include setuptools cmake cffi typing
if [[ $CUDA_VERSION == 9 ]]; then
  conda install -yc pytorch magma-cuda80
else
  conda install -yc pytorch magma-cuda90
fi
python3 setup.py install


# Install Caffe2
yes | pip install future
mkdir -p build_caffe2 && pushd build_caffe2
cmake \
  -DPYTHON_INCLUDE_DIR=$(python -c 'from distutils import sysconfig; print(sysconfig.get_python_inc())') \
  -DPYTHON_EXECUTABLE=$(which python) \
  -DUSE_ATEN=ON -DUSE_OPENCV=OFF -DUSE_LEVELDB=OFF -DUSE_LMDB=OFF -DBUILD_TEST=OFF \
  -DCMAKE_PREFIX_PATH=/opt/conda -DCMAKE_INSTALL_PREFIX=/opt/conda ..
make install "-j$(nproc)"
popd
popd


# Install ONNX
git clone --recursive https://github.com/onnx/onnx.git
PROTOBUF_INCDIR=/opt/conda/include pip install ./onnx


# Install translate
git clone --recursive https://github.com/pytorch/translate.git && pushd translate
python3 setup.py build develop

pushd pytorch_translate/cpp
# If you need to specify a compiler other than the default one cmake is picking
# up, you can use the -DCMAKE_C_COMPILER and -DCMAKE_CXX_COMPILER flags.
cmake -DCMAKE_PREFIX_PATH=/opt/conda/usr/local -DCMAKE_INSTALL_PREFIX=/opt/conda .
make
popd
'''
    }

    publishers {
      archiveArtifacts {
        allowEmpty()
        pattern('build*/CMakeCache.txt')
      }
      archiveArtifacts {
        allowEmpty()
        pattern('build*/CMakeFiles/*.log')
      }
    }
  } // source installs


  ////////////////////////////////////////////////////////////////////////////
  // Jobs that builds translate with binary installs of Pytorch and Caffe2
  ////////////////////////////////////////////////////////////////////////////
  job("${translateBasePath}/${buildEnvironment}-binary") {
    JobUtil.common(delegate, 'docker && ((cpu && ccache) || cpu_ccache)')

    parameters {
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.CMAKE_ARGS(delegate)

      stringParam(
        'DOCKER_COMMIT_TAG',
        '${DOCKER_IMAGE_TAG}-adhoc-${BUILD_ID}',
        "Tag of the Docker image to commit and push upon completion " +
          "(${buildEnvironment}:DOCKER_COMMIT_TAG)",
      )
    }

    steps {

      environmentVariables {
        env('BUILD_ENVIRONMENT', "${buildEnvironment}")
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              commitImage: dockerImage('${DOCKER_COMMIT_TAG}'),
              // TODO: use 'host-copy'. Make sure you copy out the archived artifacts
              workspaceSource: "host-mount",
              script: '''
set -ex


# Ensure jenkins can write to the ccache root dir.
sudo chown jenkins:jenkins "${HOME}/.ccache"
sudo chown -R jenkins:jenkins '/opt/conda'
export PATH=/opt/conda/bin:$PATH

# Make ccache log to the workspace, so we can archive it after the build
mkdir -p build
ccache -o log_file=$PWD/build/ccache.log

echo "$(which python)"
echo "$(which python3)"
echo "$(python --version)"
# Install translate
git clone --recursive https://github.com/pytorch/translate.git && pushd translate
python3 setup.py build develop

# Install Caffe2 and Pytorch
if [[ $CUDA_VERSION == 8* ]]; then
  conda install -y -c pytorch magma-cuda80
  conda install -y -c caffe2 pytorch-caffe2_cuda8.0_cudnn7
else
  conda install -y -c pytorch magma-cuda90
  conda install -y -c caffe2 pytorch-caffe2_cuda9.0_cudnn7
fi


# Install ONNX
git clone --recursive https://github.com/onnx/onnx.git
PROTOBUF_INCDIR=/opt/conda/include pip install ./onnx



pushd pytorch_translate/cpp
# If you need to specify a compiler other than the default one cmake is picking
# up, you can use the -DCMAKE_C_COMPILER and -DCMAKE_CXX_COMPILER flags.
cmake -DCMAKE_PREFIX_PATH=/opt/conda/usr/local -DCMAKE_INSTALL_PREFIX=/opt/conda .
make
popd
'''
    }

    publishers {
      archiveArtifacts {
        allowEmpty()
        pattern('build*/CMakeCache.txt')
      }
      archiveArtifacts {
        allowEmpty()
        pattern('build*/CMakeFiles/*.log')
      }
    }
  } // binary-installs
} // all build environments
