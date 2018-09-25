import ossci.DockerUtil
import ossci.JobUtil
import ossci.MacOSUtil
import ossci.ParametersUtil
import ossci.PhaseJobUtil
import ossci.WindowsUtil
import ossci.GitUtil
import ossci.EmailUtil
import ossci.horizon.DockerVersion
import ossci.horizon.Images
import ossci.pytorch.Users

def buildBasePath = 'horizon-builds'

folder(buildBasePath) {
  description 'Jobs for all horizon build environments'
}

// Every build environment has its own Docker image
def dockerImage = { buildEnvironment, tag ->
  return "308535385114.dkr.ecr.us-east-1.amazonaws.com/horizon/${buildEnvironment}:${tag}"
}

def mailRecipients = "kittipat@fb.com jjg@fb.com edoardoc@fb.com"

def pytorchbotAuthId = 'd4d47d60-5aa5-4087-96d2-2baa15c22480'

def masterJobSettings = { context, repo, commitSource, localMailRecipients ->
  context.with {
    JobUtil.masterTrigger(delegate, repo, "master")
    parameters {
      ParametersUtil.RUN_DOCKER_ONLY(delegate)
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    }
    steps {
      def gitPropertiesFile = './git.properties'
      GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

      phase("Master jobs") {
        Images.dockerImages.each {
          def buildEnvironment = it;
          phaseJob("${buildBasePath}/${it}-trigger") {
            parameters {
              // Pass parameters of this job
              currentBuild()
              // Checkout this exact same revision in downstream builds.
              gitRevision()
              propertiesFile(gitPropertiesFile)
              predefinedProp('COMMIT_SOURCE', commitSource)
              predefinedProp('GITHUB_REPO', repo)
            }
          }
        }
      }
    }
    publishers {
      mailer(localMailRecipients, false, true)
    }
  }
}

multiJob("horizon-master") {
  masterJobSettings(delegate, "facebookresearch/Horizon", "master", mailRecipients)
}

def pullRequestJobSettings = { context, repo, commitSource ->
  context.with {
    JobUtil.gitHubPullRequestTrigger(delegate, repo, pytorchbotAuthId, Users)
    parameters {
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    }
    steps {
      def gitPropertiesFile = './git.properties'

      GitUtil.mergeStep(delegate)
      GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

      environmentVariables {
        propertiesFile(gitPropertiesFile)
      }

      phase("Build and test") {
        Images.dockerImages.each {
          phaseJob("${buildBasePath}/${it}-trigger") {
            parameters {
              // Pass parameters of this job
              currentBuild()
              // See https://github.com/jenkinsci/ghprb-plugin/issues/591
              predefinedProp('ghprbCredentialsId', pytorchbotAuthId)
              predefinedProp('COMMIT_SOURCE', commitSource)
              predefinedProp('GITHUB_REPO', repo)
              // Ensure consistent merge behavior in downstream builds.
              propertiesFile(gitPropertiesFile)
            }
          }
        }
      }
    }
  }
}

multiJob("horizon-pull-request") {
  pullRequestJobSettings(delegate, "facebookresearch/Horizon", "pull-request")
}

// One job per build environment
Images.dockerImages.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  multiJob("${buildBasePath}/${buildEnvironment}-trigger") {
    JobUtil.commonTrigger(delegate)
    JobUtil.subJobDownstreamCommitStatus(delegate, buildEnvironment)

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.COMMIT_SOURCE(delegate)
      ParametersUtil.GITHUB_REPO(delegate, 'facebookresearch/Horizon')
    }

    steps {
      def builtImageTag = '${DOCKER_IMAGE_TAG}-${BUILD_ID}'
      def builtImageId = '${BUILD_ID}'

      phase("Build and Test") {
        phaseJob("${buildBasePath}/${buildEnvironment}-build-test") {
          parameters {
            currentBuild()
            predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
            predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
            predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
          }
        }
      }
    } // steps
  } // multiJob("${buildBasePath}/${buildEnvironment}-trigger")

  job("${buildBasePath}/${buildEnvironment}-build-test") {
    JobUtil.common delegate, 'docker && gpu'
    JobUtil.gitCommitFromPublicGitHub(delegate, '${GITHUB_REPO}')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)

      ParametersUtil.GITHUB_REPO(delegate, 'facebookresearch/Horizon')
    }

    steps {
      GitUtil.mergeStep(delegate)

      environmentVariables {
        // TODO: Will be obsolete once this is baked into docker image
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
              image: dockerImage('${BUILD_ENVIRONMENT}','${DOCKER_IMAGE_TAG}'),
              workspaceSource: "host-copy",
              script: '''
.jenkins/build.sh
'''
    }

    publishers {
      groovyPostBuild {
        script(EmailUtil.sendEmailScript)
      }
    }
  }
} // buildEnvironments.each
