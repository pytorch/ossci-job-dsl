import ossci.DockerUtil
import ossci.JobUtil
import ossci.ParametersUtil
import ossci.GitUtil
import ossci.onnx.Users
import ossci.caffe2.DockerVersion

def buildBasePath = 'onnx-builds'

folder(buildBasePath) {
  description 'Jobs for all onnx build environments'
}

// Build environments for ONNX
// These correspond to the names of the Docker images for these environments.
def buildEnvironments = [
  'py2-gcc5-ubuntu16.04',
  'py3.5-gcc5-ubuntu16.04',
]

// Runs on pull requests
multiJob("onnx-pull-request") {
  // onnxbot
  JobUtil.gitHubPullRequestTrigger(delegate, "onnx/onnx", 'bfa5e613-eeb9-44f4-b949-59680dd6d6c4', Users, true /* reportStatus */)
  parameters {
    // This defaults to ${sha1} so that it works with the GitHub pull request
    // builder plugin by default (this plugin emits the 'sha1' parameter).
    // GIT_COMMIT is more descriptive and is used everywhere else.
    stringParam(
      'GIT_COMMIT',
      '${sha1}',
      'Refspec of commit to use (e.g. origin/master)',
    )
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
  }
  concurrentBuild()
  steps {
    phase("Build") {
      buildEnvironments.each {
        phaseJob("${buildBasePath}/${it}")
      }
    }
  }
}

// Runs on master
multiJob("onnx-master") {
  JobUtil.masterTrigger(delegate, "onnx/onnx", "master")
  steps {
    phase("Build") {
      buildEnvironments.each {
        phaseJob("${buildBasePath}/${it}") {
          parameters {
            // Use exact same version of master as the trigger job.
            // This avoids race conditions.
            gitRevision()
          }
        }
      }
    }
  }
}

// One job per build environment
buildEnvironments.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  // Every build environment has its own Docker image
  def dockerImage = { tag ->
    return "308535385114.dkr.ecr.us-east-1.amazonaws.com/caffe2/${buildEnvironment}:${tag}"
  }

  job("${buildBasePath}/${buildEnvironment}") {
    JobUtil.common(delegate, 'docker && cpu')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'onnx/onnx')

    parameters {
      // This defaults to ${sha1} so that it can propagate from the
      // onnx-pull-request job to this one (used by the GitHub pull
      // request builder plugin). GIT_COMMIT is more descriptive...
      stringParam(
        'GIT_COMMIT',
        '${sha1}',
        'Refspec of commit to use (e.g. origin/master)',
      )
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    }

    steps {
      GitUtil.mergeStep(delegate)

      environmentVariables {
        env(
          'BUILD_ENVIRONMENT',
          "${buildEnvironment}",
        )
        env(
          'SCCACHE_BUCKET',
          'ossci-compiler-cache',
        )
        env(
          "CI",
          "true",
        )
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              workspaceSource: "host-mount",
              script: '''
set -ex

git submodule update --init --recursive

TOP_DIR="$PWD"

OS="$(uname)"

compilers=(
    cc
    c++
    gcc
    g++
    x86_64-linux-gnu-gcc
)

# setup ccache
if [[ "$OS" == "Darwin" ]]; then
    export PATH="/usr/local/opt/ccache/libexec:$PATH"
else
    if ! hash sccache 2>/dev/null; then
        echo "SCCACHE_BUCKET is set but sccache executable is not found"
        exit 1
    fi
    SCCACHE_BIN_DIR="$TOP_DIR/sccache"
    mkdir -p "$SCCACHE_BIN_DIR"
    for compiler in "${compilers[@]}"; do
        (
            echo "#!/bin/sh"
            echo "exec $(which sccache) $(which $compiler) \\\"\\\$@\\\""
        ) > "$SCCACHE_BIN_DIR/$compiler"
        chmod +x "$SCCACHE_BIN_DIR/$compiler"
    done
    export PATH="$SCCACHE_BIN_DIR:$PATH"
fi


# setup virtualenv
VENV_DIR=/tmp/venv

if [[ "${BUILD_ENVIRONMENT}" == py2-* ]]; then
    python2 -m virtualenv "$VENV_DIR"
elif [[ "${BUILD_ENVIRONMENT}" == py3* ]]; then
    python3 -m virtualenv "$VENV_DIR"
else
    echo "Unable to detect Python version from BUILD_ENVIRONMENT='$BUILD_ENVIRONMENT'" >&2
    exit 1
fi
source "$VENV_DIR/bin/activate"
pip install -U pip setuptools

# install test requirements
pip install pytest-cov nbval

# checkout pytorch to run integration tests
PYTORCH_DIR=/tmp/pytorch
ONNX_DIR="$PYTORCH_DIR/third_party/onnx"
git clone --recursive https://github.com/pytorch/pytorch.git "$PYTORCH_DIR"
rm -rf "$ONNX_DIR"
cp -r "$PWD" "$ONNX_DIR"
cd "$PYTORCH_DIR"

# install everything
./scripts/onnx/install-develop.sh

# run onnx tests
cd "$ONNX_DIR" && catchsegv pytest && cd -

# run integration tests
./scripts/onnx/test.sh -p

'''
    }
  }
}
