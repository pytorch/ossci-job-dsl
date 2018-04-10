import ossci.DockerUtil
import ossci.GitUtil
import ossci.JobUtil
import ossci.ParametersUtil
import ossci.tensorcomp.Users
import ossci.tensorcomp.DockerImages
import ossci.tensorcomp.DockerVersion

def buildBasePath = 'tensorcomp-builds'

folder(buildBasePath) {
  description 'Jobs for all Tensor Comprehensions build environments'
}

def dockerBuildEnvironments = DockerImages.images

def dockerImageTagParameter(context, defaultValue = DockerVersion.version) {
  context.with {
    stringParam(
      'DOCKER_IMAGE_TAG',
      defaultValue,
      'Tag of Docker image to use in downstream builds',
    )
  }
}

def caffe2botAuthId = 'e8c3034a-549f-432f-b811-ec4bbc4b3d62'

// Runs on pull requests
multiJob("tensorcomp-pull-request") {
  JobUtil.gitHubPullRequestTrigger(delegate, 'facebookresearch/TensorComprehensions', caffe2botAuthId, Users)

  parameters {
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
  }

  steps {
    def gitPropertiesFile = './git.properties'

    // Merge this pull request to master
    GitUtil.mergeStep(delegate)
    GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

    phase("Build and Test") {
      def definePhaseJob = { name ->
        phaseJob("${buildBasePath}/${name}") {
          parameters {
            // Pass parameters of this job
            currentBuild()
            // See https://github.com/jenkinsci/ghprb-plugin/issues/591
            predefinedProp('ghprbCredentialsId', caffe2botAuthId)
            // Ensure consistent merge behavior in downstream builds.
            propertiesFile(gitPropertiesFile)
          }
        }
      }

      dockerBuildEnvironments.each {
        definePhaseJob(it + "-trigger-build-test")
      }
    }
  }
}

// Runs on release build on master
multiJob("tensorcomp-master") {
  JobUtil.masterTrigger(delegate, "facebookresearch/TensorComprehensions")

  parameters {
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
  }

  steps {
    phase("Build") {
      def definePhaseJob = { name ->
        phaseJob("${buildBasePath}/${name}") {
          parameters {
            // Checkout this exact same revision in downstream builds.
            gitRevision()
            // Pass parameters of this job
            currentBuild()
          }
        }
      }

      dockerBuildEnvironments.each {
        definePhaseJob(it + "-trigger-build-test")
      }
    }
  }
}

// One job per build environment
dockerBuildEnvironments.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  // Every build environment has its own Docker image
  def dockerImage = { tag ->
    return "308535385114.dkr.ecr.us-east-1.amazonaws.com/tensorcomp/${buildEnvironment}:${tag}"
  }

  // Trigger jobs are multi jobs (it may trigger both a build and a test job)
  multiJob("${buildBasePath}/${buildEnvironment}-trigger-build-test") {
    JobUtil.commonTrigger(delegate)
    JobUtil.subJobDownstreamCommitStatus(delegate, buildEnvironment)

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      ParametersUtil.DOCKER_IMAGE_TAG(delegate, buildEnvironment)
    }

    steps {
      def builtImageTag = '${DOCKER_IMAGE_TAG}-build-test-${BUILD_ID}'

      phase("Build") {
        phaseJob("${buildBasePath}/${buildEnvironment}-build-test") {
          parameters {
            currentBuild()
            predefinedProp('DOCKER_COMMIT_TAG', builtImageTag)
            predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
            predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
          }
        }
      }

    }
  }

  // The actual build-test job for this build/test environment
  job("${buildBasePath}/${buildEnvironment}-build-test") {
    JobUtil.common(delegate, 'docker && gpu_ccache')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'facebookresearch/TensorComprehensions')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      dockerImageTagParameter(delegate)
    }

    wrappers {
      timestamps()
    }

    steps {
      GitUtil.mergeStep(delegate)

      def cudaVersion = ''
      if (buildEnvironment.contains('cuda')) {
        // 'native' indicates to let the nvidia runtime figure out which version
        // of CUDA to use. This is only possible when using the nvidia/cuda
        // Docker images.
        cudaVersion = 'native';
      }

      DockerUtil.shell context: delegate,
          image: dockerImage('${DOCKER_IMAGE_TAG}'),
          cudaVersion: cudaVersion,
          // workspaceSource: "host-copy",
          importEnv: 0,  // we want to use the docker env and not import the outside env
          script: '''
set -ex

# Reinitialize submodules
git submodule update --init --recursive

echo "Install TC"
export MAX_JOBS=2
.jenkins/build.sh

echo "Test TC"
.jenkins/run_test.sh
exit 0
'''
    }
  }
}
