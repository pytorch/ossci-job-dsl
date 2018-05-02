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

  job("${translateBasePath}/${buildEnvironment}") {
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


git clone --recursive https://github.com/pytorch/pytorch.git && pushd pytorch


# Install pytorch
yes | conda install numpy pyyaml mkl mkl-include setuptools cmake cffi typing
yes | conda install -c pytorch magma-cuda80 # or magma-cuda90 if CUDA 9
python3 setup.py install


# Install Caffe2
pip install -y future
mkdir -p build_caffe2 && pushd build_caffe2
cmake \
  -DPYTHON_INCLUDE_DIR=$(python -c 'from distutils import sysconfig; print(sysconfig.get_python_inc())') \
  -DPYTHON_EXECUTABLE=$(which python) \
  -DUSE_ATEN=ON -DUSE_OPENCV=OFF -DUSE_LEVELDB=OFF -DUSE_LMDB=OFF \
  -DCMAKE_PREFIX_PATH=/opt/conda -DCMAKE_INSTALL_PREFIX=/opt/conda ..
make install -j8 2>&1
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
  } // job
}
