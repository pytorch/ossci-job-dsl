import ossci.DockerUtil
import ossci.GitUtil
import ossci.ParametersUtil

// WARNING: THIS IS OUT OF DATE. CHECK tensorcomp.groovy FOR A BETTER TEMPLATE

def buildBasePath = 'skeleton-builds'

folder(buildBasePath) {
  description 'Jobs for all <your project> build environments'
}

def githubRepository = "someorg/somerepo"

def dockerBuildEnvironments = [
  "docker-image1-name",
  "docker-image2-name",
]

// Users that can ask @caffe2bot to trigger build on behalf
// of a user that is not an admin or present in the whitelist.
//
// Consider moving this to ./src/main/groovy/ossci/<your project>
// and modeling it after the Caffe2 and PyTorch configs.
//
def projectAdminUsers = [
  'pietern',
  'ezyang',
]

// Users for who pull request builds are automatically started.
//
// Consider moving this to ./src/main/groovy/ossci/<your project>
// and modeling it after the Caffe2 and PyTorch configs.
//
def projectWhitelistUsers = [
  'pietern',
  'ezyang',
]

// dockerImageTag defines a build parameter that you can
// manually override in the Jenkins UI. This is used whenever you're testing
// a new image and don't want to break master or pending pull requests
// while you're working it. This is extracted into a helper function
// because it is repeated for every job.
def dockerImageTagParameter(context, defaultValue = 'latest') {
  context.with {
    stringParam(
      'DOCKER_IMAGE_TAG',
      defaultValue,
      'Tag of Docker image to use in downstream builds',
    )
  }
}

// Runs on pull requests
multiJob("skeleton-pull-request") {
  label('simple')

  concurrentBuild()

  parameters {
    ParametersUtil.GIT_COMMIT(delegate, '${ghprbActualCommit}')
    ParametersUtil.GIT_MERGE_TARGET(delegate, 'origin/${ghprbTargetBranch}')

    dockerImageTagParameter(delegate)
  }

  scm {
    git {
      remote {
        github(githubRepository)
        refspec([
            // Fetch remote branches so we can merge the PR
            '+refs/heads/*:refs/remotes/origin/*',
            // Fetch PRs
            '+refs/pull/*:refs/remotes/origin/pr/*',
          ].join(' '))
      }
      branch('${GIT_COMMIT}')
    }
  }

  triggers {
    githubPullRequest {
      admins(projectAdminUsers)
      userWhitelist(projectWhitelistUsers)
      useGitHubHooks()
      whiteListTargetBranches(['master'])
    }
  }

  steps {
    def gitPropertiesFile = './git.properties'

    // Merge this pull request to master
    GitUtil.mergeStep(delegate)

    // Convert refspecs (e.g. origin/pr/1234/head) to commit SHA
    // and save them in the properties file.
    // They can change DURING the build and we want to make
    // sure we always test the SAME COMMIT.
    GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

    phase("Build") {
      // For the sake of example this mixes build-only with build-and-test triggers.
      // For both Caffe2 and PyTorch we have a number of configurations where
      // we care that compilation succeeds, but where test coverage is provided
      // by other builds. In this case it would be wasteful to run tests for these
      // builds as well, so we only trigger build-only jobs on pull requests.

      def buildOnlyEnvironments = [
        "docker-image1-name",
      ]

      def buildAndTestEnvironments = [
        "docker-image2-name",
      ]

      def definePhaseJob = { name ->
        phaseJob("${buildBasePath}/${name}") {
          parameters {
            // Pass parameters of this job
            currentBuild()
            // See https://github.com/jenkinsci/ghprb-plugin/issues/591
            // This MUST be changed if you are using a bot that is NOT @caffe2bot.
            predefinedProp('ghprbCredentialsId', 'e8c3034a-549f-432f-b811-ec4bbc4b3d62')
            // Ensure consistent merge behavior in downstream builds.
            propertiesFile(gitPropertiesFile)
          }
        }
      }

      buildAndTestEnvironments.each {
        definePhaseJob(it + "-trigger-test")
      }

      buildOnlyEnvironments.each {
        definePhaseJob(it + "-trigger-build")
      }
    }
  }

  // See caffe2.groovy and/or pytorch.groovy for another section that
  // must be present in the pull request trigger job. This is not
  // included here to keep this focused.
}

// Runs on release build on master
multiJob("skeleton-master") {
  label('simple')

  concurrentBuild()

  parameters {
    dockerImageTagParameter(delegate)
  }

  scm {
    git {
      remote {
        github(githubRepository)
        refspec('+refs/heads/master:refs/remotes/origin/master')
      }
      branch('origin/master')
    }
  }

  triggers {
    githubPush()
  }

  steps {
    phase("Build") {
      // Trigger test build for all our environments.
      // Keep in mind that this is an example. You may not
      // want to trigger test builds for all your environments.
      dockerBuildEnvironments.each {
        phaseJob("${buildBasePath}/${it}-trigger-test") {
          parameters {
            // Checkout this exact same revision in downstream builds.
            gitRevision()
            // Pass parameters of this job
            currentBuild()
          }
        }
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
    return "registry.pytorch.org/<your project>/${buildEnvironment}:${tag}"
  }

  // Create triggers for build-only and build-and-test.
  // The build only trigger is used for build environments where we
  // only care the code compiles and assume test coverage is provided
  // by other builds (for example: different compilers).
  [false, true].each {
    def runTests = it

    // jobName is the name of THIS trigger job
    def jobName = "${buildBasePath}/${buildEnvironment}-trigger"
    if (!runTests) {
      jobName += "-build"
    } else {
      jobName += "-test"
    }

    // gitHubName is the name you see used in GitHub commit status
    def gitHubName = "${buildEnvironment}"
    if (!runTests) {
      gitHubName += "-build"
    } else {
      gitHubName += "-test"
    }

    // Trigger jobs are multi jobs (it may trigger both a build and a test job)
    multiJob(jobName) {
      label('simple')

      concurrentBuild()

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)

        dockerImageTagParameter(delegate)
      }

      wrappers {
        downstreamCommitStatus {
          context("pr/${gitHubName}")

          // No need to inform when build is triggered...
          // The '--none--' constant makes the ghprb plugin skip this.
          triggeredStatus('--none--')

          // Only inform when build is started...
          startedStatus('Build in progress')

          // Posted upon completion of this job
          completedStatus('SUCCESS', 'Build successful')
          completedStatus('FAILURE', 'Build failed')
          completedStatus('PENDING', 'Build pending')
          completedStatus('ERROR', 'Build errored')
        }
      }

      scm {
        git {
          remote {
            github(githubRepository)
            refspec([
                // Fetch all branches
                '+refs/heads/*:refs/remotes/origin/*',
                // Fetch PRs so we can trigger from caffe2-pull-request
                '+refs/pull/*:refs/remotes/origin/pr/*',
              ].join(' '))
          }
          branch('${GIT_COMMIT}')
        }
      }

      steps {
        def gitPropertiesFile = './git.properties'

        GitUtil.mergeStep(delegate)

        // This is duplicated from the pull request trigger job such that
        // you don't need a pull request trigger job to test any branch
        // after merging it into any other branch (not just origin/master).
        GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

        // Different triggers (build and run tests or build only)
        // means we have to use different tags, or we risk conflicting
        // prefixes (rare, but possible).
        def builtImagePrefix = ''
        if (runTests) {
          builtImageTag = '${DOCKER_IMAGE_TAG}-build-test-${BUILD_ID}'
        } else {
          builtImageTag = '${DOCKER_IMAGE_TAG}-build-${BUILD_ID}'
        }

        // Set these variables so they propagate to the publishers below.
        environmentVariables {
          env(
            'BUILD_ENVIRONMENT',
            "${buildEnvironment}",
          )
          env(
            'BUILT_IMAGE_TAG',
            "${builtImageTag}",
          )
        }

        phase("Build") {
          phaseJob("${buildBasePath}/${buildEnvironment}-build") {
            parameters {
              currentBuild()
              propertiesFile(gitPropertiesFile)
              predefinedProp('DOCKER_COMMIT_TAG', builtImageTag)
            }
          }
        }

        // Also trigger test job if this is a build-and-test trigger
        // Note that the DOCKER_IMAGE_TAG uses the tag that was
        // produced by the build job above.
        if (runTests) {
          phase("Test") {
            phaseJob("${buildBasePath}/${buildEnvironment}-test") {
              parameters {
                currentBuild()
                propertiesFile(gitPropertiesFile)
                predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
              }
            }
          }
        }
      }
    }
  }

  // The actual build job for this build environment
  job("${buildBasePath}/${buildEnvironment}-build") {
    label('docker && cpu')

    concurrentBuild()

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      dockerImageTagParameter(delegate)

      stringParam(
        'DOCKER_COMMIT_TAG',
        '${DOCKER_IMAGE_TAG}-adhoc-${BUILD_ID}',
        "Tag of the Docker image to commit and push upon completion " +
          "(${buildEnvironment}:DOCKER_COMMIT_TAG)",
      )
    }

    wrappers {
      timestamps()
      credentialsBinding {
        usernamePassword('USERNAME', 'PASSWORD', 'nexus-jenkins')
      }
    }

    scm {
      git {
        remote {
          github(githubRepository)
          refspec([
              // Fetch all branches
              '+refs/heads/*:refs/remotes/origin/*',
              // Fetch PRs so we can trigger from caffe2-pull-request
              '+refs/pull/*:refs/remotes/origin/pr/*',
            ].join(' '))
        }
        branch('${GIT_COMMIT}')
        GitUtil.defaultExtensions(delegate)
      }
    }

    steps {
      GitUtil.mergeStep(delegate)

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              commitImage: dockerImage('${DOCKER_COMMIT_TAG}'),
              registryCredentials: ['${USERNAME}', '${PASSWORD}'],
              workspaceSource: "host-mount",
              script: '''
set -ex

# YOUR BUILD SCRIPT GOES HERE

# This shell code is executed in the Docker container for this
# build environment (at the specified image tag).
'''
    }
  }

  // The actual test job for this build environment
  job("${buildBasePath}/${buildEnvironment}-test") {
    // Run tests on GPU machine if built with CUDA support
    if (buildEnvironment.contains('cuda')) {
      label('docker && gpu')
    } else {
      label('docker && cpu')
    }

    concurrentBuild()

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      dockerImageTagParameter(delegate)
    }

    wrappers {
      timestamps()
      timeout {
        absolute(20)
        failBuild()
        writeDescription('Build failed due to timeout after {0} minutes')
      }
      credentialsBinding {
        usernamePassword('USERNAME', 'PASSWORD', 'nexus-jenkins')
      }
    }

    scm {
      git {
        remote {
          github(githubRepository)
          refspec([
              // Fetch all branches
              '+refs/heads/*:refs/remotes/origin/*',
              // Fetch PRs so we can trigger from caffe2-pull-request
              '+refs/pull/*:refs/remotes/origin/pr/*',
            ].join(' '))
        }
        branch('${GIT_COMMIT}')
        GitUtil.defaultExtensions(delegate)
      }
    }

    steps {
      GitUtil.mergeStep(delegate)

      environmentVariables {
        env(
          'BUILD_ENVIRONMENT',
          "${buildEnvironment}",
        )
      }

      def cudaVersion = ''
      if (buildEnvironment.contains('cuda')) {
        // 'native' indicates to let the nvidia runtime
        // figure out which version of CUDA to use.
        // This is only possible when using the nvidia/cuda
        // Docker images.
        cudaVersion = 'native';
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              registryCredentials: ['${USERNAME}', '${PASSWORD}'],
              cudaVersion: cudaVersion,
              workspaceSource: "host-mount",
              script: '''
set -ex

# YOUR TEST SCRIPT GOES HERE
'''
    }

    publishers {
      // See caffe2.groovy and pytorch.groovy for inspiration.
    }
  }
}
