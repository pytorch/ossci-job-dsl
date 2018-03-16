import ossci.DockerUtil
import ossci.GitUtil
import ossci.JobUtil
import ossci.ParametersUtil

def buildBasePath = 'yf225-builds'

folder(buildBasePath) {
  description 'yf225 test jobs'
}

def buildEnvironments = [
  "yf225-linux-xenial-cuda8-cudnn6-py3",
]

// Runs on master
multiJob("yf225-master") {
  JobUtil.masterTrigger(delegate, "yf225/test-repo")
  steps {
    def gitPropertiesFile = './git.properties'
    GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

    phase("Master jobs") {
      buildEnvironments.each {
        def buildEnvironment = it;
        phaseJob("${buildBasePath}/${it}-trigger") {
          parameters {
            // Checkout this exact same revision in downstream builds.
            gitRevision()
            propertiesFile(gitPropertiesFile)
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
  multiJob("${buildBasePath}/${buildEnvironment}-trigger") {
    JobUtil.commonTrigger(delegate)
    JobUtil.subJobDownstreamCommitStatus(delegate, buildEnvironment)

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
    }

    steps {
      phase("Build") {
        phaseJob("${buildBasePath}/${buildEnvironment}-build") {
          parameters {
            currentBuild()
            predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
            predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
          }
        }
      }
      phase("Test and Push") {
        phaseJob("${buildBasePath}/${buildEnvironment}-test") {
          parameters {
            currentBuild()
            predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
            predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
          }
        }
      }
    }
  }

  job("${buildBasePath}/${buildEnvironment}-build") {
    JobUtil.common delegate, 'docker && cpu'
    JobUtil.gitCommitFromPublicGitHub delegate, "yf225/test-repo"

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
    }

    steps {
      GitUtil.mergeStep(delegate)

      shell("sleep 1m")
    }
  }

  job("${buildBasePath}/${buildEnvironment}-test") {
    JobUtil.common delegate, 'docker && cpu'
    JobUtil.gitCommitFromPublicGitHub(delegate, "yf225/test-repo")
    JobUtil.timeoutAndFailAfter(delegate, 30)

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
    }

    steps {
      GitUtil.mergeStep(delegate)
    }
  }
}