import ossci.DockerUtil
import ossci.JobUtil
import ossci.ParametersUtil
import ossci.onnx.Users
// TODO: give onnx its own dockerfiles repo and then stop using caffe2's
// images
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
  JobUtil.masterTrigger(delegate, "onnx/onnx")
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
      environmentVariables {
        env(
          'BUILD_ENVIRONMENT',
          "${buildEnvironment}",
        )
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              workspaceSource: "host-mount",
              script: '''
set -ex

git submodule update --init --recursive

if [[ "${BUILD_ENVIRONMENT}" == py2-* ]]; then
  PYTHON=python2
fi

if [[ "${BUILD_ENVIRONMENT}" == py3* ]]; then
  PYTHON=python3
fi

echo "Python version:"
which $PYTHON
$PYTHON --version

# create virtualenv
$PYTHON -m virtualenv venv && source venv/bin/activate
pip install -U pip setuptools

# install
pip install ninja
pip install -e .

# run tests
pip install pytest-cov nbval
pytest

# check auto-gen files up-to-date
$PYTHON onnx/defs/gen_doc.py -o docs/Operators.md
$PYTHON onnx/gen_proto.py
git diff --exit-code

deactivate

echo "ALL CHECKS PASSED"
'''
    }
  }
}
