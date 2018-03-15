import ossci.DockerUtil
import ossci.JobUtil
import ossci.ParametersUtil
import ossci.GitUtil
import ossci.onnx.Users
// TODO: give onnx its own dockerfiles repo and then stop using caffe2's
// images
import ossci.caffe2.DockerVersion

def buildBasePath = 'onnx-fb-universe-builds'

folder(buildBasePath) {
  description 'Jobs for all onnx-fb-universe build environments'
}

// Build environments for onnx-fb-universe
// These correspond to the names of the Docker images for these environments.
def buildEnvironments = [
  'py2-gcc5-ubuntu16.04',
]

// Runs on pull requests
multiJob("onnx-fb-universe-pull-request") {
  JobUtil.gitHubPullRequestTrigger(delegate, "onnxbot/onnx-fb-universe", 'bfa5e613-eeb9-44f4-b949-59680dd6d6c4', Users, true /* report status */)

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
  // ONNX has automatically triggered builds, so we rate limit it to prevent it from
  // starving other projects
  throttleConcurrentBuilds {
    maxTotal(10)
  }
  steps {
    phase("Build") {
      buildEnvironments.each {
        phaseJob("${buildBasePath}/${it}")
      }
    }
  }
}

// Runs on master
multiJob("onnx-fb-universe-master") {
  JobUtil.masterTrigger(delegate, "onnxbot/onnx-fb-universe")
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
    return "registry.pytorch.org/caffe2/${buildEnvironment}:${tag}"
  }

  job("${buildBasePath}/${buildEnvironment}") {
    JobUtil.common(delegate, 'docker && cpu')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'onnxbot/onnx-fb-universe')

     parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    }

    throttleConcurrentBuilds {
      maxTotal(10)
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
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              workspaceSource: "host-mount",
              script: '''
set -ex

# This is to avoid jenkins canceling a job in the middle of some git commands,
# which will leave the git checkout in corrupted state in next run.
time cp -r $(pwd) /tmp/code && cd /tmp/code

git submodule update --init --recursive || true
git submodule foreach git fetch --tags --progress origin +refs/pull/*:refs/remotes/origin/pr/*
git submodule update --init --recursive

source jenkins/setup.sh
./jenkins/build.sh
./jenkins/test.sh

echo "ALL CHECKS PASSED"
'''
    }
  }
}

// Runs model zoo generation for caffe2-derived models
job("${buildBasePath}/onnx-model-zoo-update-caffe2-models") {
  JobUtil.common(delegate, 'docker && cpu')
  JobUtil.gitCommitFromPublicGitHub(delegate, 'onnxbot/onnx-fb-universe')

  def docEnvironment = 'py3.6-gcc5-ubuntu16.04'

  // Every build environment has its own Docker image
  def dockerImage = { tag ->
    return "registry.pytorch.org/caffe2/${docEnvironment}:${tag}"
  }

  parameters {
    ParametersUtil.GIT_COMMIT(delegate)
    ParametersUtil.GIT_MERGE_TARGET(delegate)
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
  }

  steps {
      GitUtil.mergeStep(delegate)

      environmentVariables {
        env(
          'SCCACHE_BUCKET',
          'ossci-compiler-cache',
        )
      }
      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              workspaceSource: "host-mount",
              script: '''
set -ex

git submodule update --init --recursive || true
git submodule foreach git fetch --tags --progress origin +refs/pull/*:refs/remotes/origin/pr/*
git submodule update --init --recursive

source jenkins/setup.sh

pip install awscli filechunkio boto3

./jenkins/build.sh
python -u ./scripts/update-models-from-caffe2.py
'''
  }
}
