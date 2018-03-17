import ossci.DockerUtil
import ossci.JobUtil
import ossci.MacOSUtil
import ossci.ParametersUtil
import ossci.PhaseJobUtil
import ossci.WindowsUtil
import ossci.GitUtil
import ossci.EmailUtil
import ossci.pytorch.Users
import ossci.pytorch.DockerVersion

def buildBasePath = 'pytorch-builds'

folder(buildBasePath) {
  description 'Jobs for all PyTorch build environments'
}

// Build environments for PyTorch
// These correspond to the names of the Docker images for these environments.
def buildEnvironments = [
  // Testing this configuration doesn't really buy us much; if it's
  // a Python 2 problem, the CUDA 9 build will catch it; if it's a CUDA
  // 8 problem, you don't need to test it on Python 2 as well.
  // "pytorch-linux-xenial-cuda8-cudnn6-py2",
  "pytorch-linux-xenial-cuda8-cudnn6-py3",
  "pytorch-linux-xenial-cuda9-cudnn7-py2",
  "pytorch-linux-xenial-cuda9-cudnn7-py3",
  "pytorch-linux-xenial-py3-clang5-asan",
  "pytorch-linux-trusty-py2.7.9",
  "pytorch-linux-trusty-py2.7",
  "pytorch-linux-trusty-py3.5",
  "pytorch-linux-trusty-py3.6-gcc4.8",
  "pytorch-linux-trusty-py3.6-gcc5.4",
  "pytorch-linux-trusty-py3.6-gcc7.2",
  "pytorch-linux-trusty-pynightly",
  "pytorch-win-ws2016-cuda9-cudnn7-py3",
  "pytorch-macos-10.13-py3",
  // This is really expensive to run because it is a total build
  // from scratch.  Maybe we have to do it nightly.
  // "pytorch-docker",
]

def experimentalBuildEnvironments = [
]

def docEnvironment = "pytorch-linux-xenial-cuda8-cudnn6-py3"
def perfTestEnvironment = "pytorch-linux-xenial-cuda8-cudnn6-py3"

// Every build environment has its own Docker image
def dockerImage = { buildEnvironment, tag ->
  return "registry.pytorch.org/pytorch/${buildEnvironment}:${tag}"
}

def mailRecipients = "ezyang@fb.com pietern@fb.com willfeng@fb.com englund@fb.com"

def ciFailureEmailScript = '''
if (manager.build.result.toString().contains("FAILURE")) {
  def logLines = manager.build.logFile.readLines()
  def isFalsePositive = (logLines.count {
    it.contains("ERROR: Couldn't find any revision to build. Verify the repository and branch configuration for this job.") /* This commit is not the latest one anymore. */ \
    || it.contains("java.lang.InterruptedException") /* Job is cancelled. */ \
    || it.contains("fatal: reference is not a tree") /* Submodule commit doesn't exist, Linux */ \
    || it.contains("Server does not allow request for unadvertised object") /* Submodule commit doesn't exist, Windows */
  } > 0)
  def isFalseNegative = (logLines.count {
    it.contains("clang: error: unable to execute command: Segmentation fault: 11") /* macOS clang segfault error */
  } > 0)
  def hasEnteredUserLand = (logLines.count {it.contains("ENTERED_USER_LAND")} > 0)
  def hasExitedUserLand = (logLines.count {it.contains("EXITED_USER_LAND")} > 0)
  def inUserLand = (hasEnteredUserLand && !hasExitedUserLand)
  if ((!inUserLand && !isFalsePositive) || isFalseNegative) {
    // manager.listener.logger.println "CI system failure occured"
    sendEmail("'''+mailRecipients+'''", 'CI system failure', 'See <'+manager.build.getEnvironment()["BUILD_URL"]+'>'+'\\n\\n'+'Log:\\n\\n'+logLines)
  }
}
'''

def pytorchbotAuthId = 'd4d47d60-5aa5-4087-96d2-2baa15c22480'

// Runs on pull requests
multiJob("pytorch-pull-request") {
  JobUtil.gitHubPullRequestTrigger(delegate, 'pytorch/pytorch', pytorchbotAuthId, Users)
  parameters {
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
  }
  steps {
    def gitPropertiesFile = './git.properties'

    GitUtil.mergeStep(delegate)
    GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

    phase("Build and test") {
      buildEnvironments.each {
        phaseJob("${buildBasePath}/${it}-trigger") {
          parameters {
            // Pass parameters of this job
            currentBuild()
            // See https://github.com/jenkinsci/ghprb-plugin/issues/591
            predefinedProp('ghprbCredentialsId', pytorchbotAuthId)
            predefinedProp('COMMIT_SOURCE', 'pull-request')
            // Ensure consistent merge behavior in downstream builds.
            propertiesFile(gitPropertiesFile)
          }
        }
      }
    }
  }
}

// Runs on master
multiJob("pytorch-master") {
  JobUtil.masterTrigger(delegate, "pytorch/pytorch")
  parameters {
    ParametersUtil.RUN_DOCKER_ONLY(delegate)
  }
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
            booleanParam('DOC_PUSH', true)
            predefinedProp('COMMIT_SOURCE', 'master')
          }
          if (!buildEnvironment.contains('linux')) {
            PhaseJobUtil.condition(delegate, '!${RUN_DOCKER_ONLY}')
          }
        }
      }
    }
  }
  publishers {
    mailer(mailRecipients, false, true)
  }
}

def lintCheckBuildEnvironment = 'pytorch-linux-trusty-py2.7'

// One job per build environment
(buildEnvironments + experimentalBuildEnvironments).each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  // This is legacy, don't copy me.  The modern approach is done in caffe2, where
  // buildEnvironment is baked into the docker image so we don't have to
  // compute here
  def cudaVersion = '';
  if ( buildEnvironment.contains('cuda8') ) {
    cudaVersion = '8'
  }
  if ( buildEnvironment.contains('cuda9') ) {
    cudaVersion = '9'
  }

  multiJob("${buildBasePath}/${buildEnvironment}-trigger") {
    JobUtil.commonTrigger(delegate)
    JobUtil.subJobDownstreamCommitStatus(delegate, buildEnvironment)

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)

      booleanParam(
        'RUN_TESTS',
        true,
        'Whether to run tests or not',
      )

      booleanParam(
        'DOC_PUSH',
        false,
        'Whether to doc push or not',
      )

      stringParam(
        'COMMIT_SOURCE',
        '',
        'Source of the commit (master or pull-request)',
      )
    }

    steps {
      def builtImageTag = '${DOCKER_IMAGE_TAG}-${BUILD_ID}'
      def builtImageId = '${BUILD_ID}'

      if (buildEnvironment.contains("macos") || buildEnvironment.contains("docker")) {
        phase("Build and Test") {
          phaseJob("${buildBasePath}/${buildEnvironment}-build-test") {
            parameters {
              currentBuild()
              predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
              predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
            }
          }
        }
      } else {
        phase("Build") {
          phaseJob("${buildBasePath}/${buildEnvironment}-build") {
            parameters {
              currentBuild()
              predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
              predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
              predefinedProp('DOCKER_IMAGE_TAG', '${DOCKER_IMAGE_TAG}')
              predefinedProp('DOCKER_IMAGE_COMMIT_TAG', builtImageTag)
              predefinedProp('IMAGE_COMMIT_ID', builtImageId)
            }
          }
        }
        phase("Test and Push") {
          phaseJob("${buildBasePath}/${buildEnvironment}-test") {
            parameters {
              currentBuild()
              predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
              predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
              predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
              predefinedProp('IMAGE_COMMIT_ID', builtImageId)
            }
          }
          if (buildEnvironment == docEnvironment) {
            phaseJob("${buildBasePath}/doc-push") {
              parameters {
                predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
                predefinedProp('DOC_PUSH', '${DOC_PUSH}')
              }
            }
          }
          if (buildEnvironment == perfTestEnvironment) {
            phaseJob("${buildBasePath}/short-perf-test-cpu") {
             parameters {
               predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
               predefinedProp('COMMIT_SOURCE', '${COMMIT_SOURCE}')
             }
            }
            // yf225: disabled due to flakiness
            // phaseJob("${buildBasePath}/short-perf-test-gpu") {
            //   parameters {
            //     predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
            //     predefinedProp('COMMIT_SOURCE', '${COMMIT_SOURCE}')
            //   }
            // }
          }
        }
      }
    }
  }

  if (buildEnvironment.contains('linux')) {
  job("${buildBasePath}/${buildEnvironment}-build") {
    JobUtil.common delegate, buildEnvironment.contains('cuda') ? 'docker && cpu && ccache' : 'docker && cpu';
    JobUtil.gitCommitFromPublicGitHub(delegate, "pytorch/pytorch")

    // Hack to make nvcc-using builds go on the ccache nodes.  Autoscaler
    // nodes (labeled docker && cpu) only have sccache, which does not
    // currently support nvcc.
    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)

      stringParam(
        'DOCKER_IMAGE_COMMIT_TAG',
        '',
        'Tag of image to commit and push after this build completes (if non-empty)',
      )
    }

    wrappers {
      credentialsBinding {
        usernamePassword('USERNAME', 'PASSWORD', 'nexus-jenkins')
      }
    }

    steps {
      GitUtil.mergeStep(delegate)

      environmentVariables {
        // TODO: Will be obsolete once this is baked into docker image
        env(
          'BUILD_ENVIRONMENT',
          "${buildEnvironment}",
        )
        // Only try to enable sccache if this is NOT a CUDA build
        // NVCC support for sccache is underway.
        if (!buildEnvironment.contains('cuda')) {
          env(
            'SCCACHE_BUCKET',
            'ossci-compiler-cache',
          )
        }
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${BUILD_ENVIRONMENT}','${DOCKER_IMAGE_TAG}'),
              commitImage: dockerImage('${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_COMMIT_TAG}'),
              registryCredentials: ['${USERNAME}', '${PASSWORD}'],
              workspaceSource: "host-copy",
              script: '''
set -ex

# Reinitialize submodules
git submodule update --init

echo "Using in-repo script"
.jenkins/build.sh

# Clean up temporary build products so that the docker image we commit
# has less size
git clean -xfd

exit 0
'''
    }

    publishers {
      groovyPostBuild {
        script(EmailUtil.sendEmailScript + ciFailureEmailScript)
      }
    }
  }


  if (buildEnvironment == docEnvironment) {
    job("${buildBasePath}/doc-push") {
      JobUtil.common delegate, 'docker && cpu && ccache'
      // Explicitly disable concurrent build because this job is racy.
      concurrentBuild(false)
      parameters {
        // TODO: Accept GIT_COMMIT, eliminate race, small profit
        ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
        booleanParam(
          'DOC_PUSH',
          false,
          'Whether to doc push or not',
        )
      }
      scm {
        git {
          remote {
            github('pytorch/pytorch.github.io', 'ssh')
            credentials('pytorchbot')
          }
          branch('origin/master')
          GitUtil.defaultExtensions(delegate)
        }
      }
      steps {
        // TODO: delete me after BUILD_ENVIRONMENT baked into docker image
        environmentVariables {
          env(
            'BUILD_ENVIRONMENT',
            "${buildEnvironment}",
          )
        }

        // TODO: Move this script into repository somewhere
        DockerUtil.shell context: delegate,
                image: dockerImage('${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_TAG}'),
                workspaceSource: "host-mount",
                script: '''
set -ex

if [ "${DOC_PUSH:-true}" == "false" ]; then
  echo "Skipping doc push..."
  exit 0
fi

export PATH=/opt/conda/bin:$PATH

rm -rf pytorch || true

# Get all the documentation sources, put them in one place
# TODO: These clones can race
git clone https://github.com/pytorch/pytorch
pushd pytorch
git clone https://github.com/pytorch/vision
pushd vision
conda install -y pillow
time python setup.py install
popd
pushd docs
rm -rf source/torchvision
cp -r ../vision/docs/source source/torchvision

# Build the docs
pip install -r requirements.txt || true
make html

# Move them into the docs repo
popd
popd
git rm -rf docs/master || true
mv pytorch/docs/build/html docs/master
find docs/master -name "*.html" -print0 | xargs -0 sed -i -E 's/master[[:blank:]]\\([[:digit:]]\\.[[:digit:]]\\.[[:xdigit:]]+\\+[[:xdigit:]]+[[:blank:]]\\)/<a href="http:\\/\\/pytorch.org\\/docs\\/versions.html">& \\&#x25BC<\\/a>/g'
git add docs/master || true
git status
git config user.email "soumith+bot@pytorch.org"
git config user.name "pytorchbot"
# If there aren't changes, don't make a commit; push is no-op
git commit -m "auto-generating sphinx docs" || true
git status
'''
      }
      // WARNING WARNING WARNING: This block is unusual!  Look twice before
      // copy pasting!
      publishers {
        git {
          // TODO: This has the race in the no-op case with DOC_PUSH=false. Oops.
          pushOnlyIfSuccess()
          branch('origin', 'master')
        }
      }
    }
  }

  if (buildEnvironment == perfTestEnvironment) {
    job("${buildBasePath}/short-perf-test-cpu") {
      JobUtil.common delegate, 'cpu-perf-test'

      parameters {
        ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
        stringParam(
          'COMMIT_SOURCE',
          '',
          'Source of the commit (master or pull-request)',
        )
      }

      scm {
        git {
          remote {
            github('yf225/perf-tests', 'ssh')
            credentials('caffe2bot')
          }
          branch('origin/cpu')
        }
      }

      wrappers {
        credentialsBinding {
          string('DBHOSTNAME', 'pytorchdb-host')
          string('DBNAME', 'pytorchdb-name')
          usernamePassword('USERNAME', 'PASSWORD', 'pytorchdb')
        }
      }

      steps {
        environmentVariables {
          env(
            'BUILD_ENVIRONMENT',
            "${buildEnvironment}",
          )
        }

        MacOSUtil.dockerShell context: delegate,
                image: dockerImage('${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_TAG}'),
                script: '.jenkins/short-perf-test-cpu.sh'
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + ciFailureEmailScript)
        }
      }

      publishers {
        git {
          pushOnlyIfSuccess()
          forcePush()
          branch('origin', 'cpu')
        }
      }
    }

    job("${buildBasePath}/short-perf-test-gpu") {
      JobUtil.common delegate, 'docker && gpu'

      parameters {
        ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
        stringParam(
          'COMMIT_SOURCE',
          '',
          'Source of the commit (master or pull-request)',
        )
      }

      scm {
        git {
          remote {
            github('yf225/perf-tests', 'ssh')
            credentials('caffe2bot')
          }
          branch('origin/gpu')
        }
      }

      wrappers {
        credentialsBinding {
          string('DBHOSTNAME', 'pytorchdb-host')
          string('DBNAME', 'pytorchdb-name')
          usernamePassword('USERNAME', 'PASSWORD', 'pytorchdb')
        }
      }

      steps {
        environmentVariables {
          env(
            'BUILD_ENVIRONMENT',
            "${buildEnvironment}",
          )
        }

        DockerUtil.shell context: delegate,
                cudaVersion: cudaVersion,
                image: dockerImage('${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_TAG}'),
                workspaceSource: "docker",
                script: '.jenkins/short-perf-test-gpu.sh'
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + ciFailureEmailScript)
        }
      }

      publishers {
        git {
          pushOnlyIfSuccess()
          forcePush()
          branch('origin', 'gpu')
        }
      }
    } // job(... + "short-perf-test-gpu")
  } // if (buildEnvironment == perfTestEnvironment)

  job("${buildBasePath}/${buildEnvironment}-test") {
    JobUtil.common delegate, buildEnvironment.contains('cuda') ? 'docker && gpu' : 'docker && cpu'
    JobUtil.timeoutAndFailAfter(delegate, 25)

    parameters {
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)

      // TODO: Using this parameter is a bit wasteful because Jenkins
      // still has to schedule the job and load the docker image
      booleanParam(
        'RUN_TESTS',
        true,
        'Whether to run tests or not',
      )
    }

    publishers {
      // NB: Dead at the moment (PyTorch test suite does not currently generate XMLs)
      archiveXUnit {
        jUnit {
          pattern('test-*.xml')
          skipNoTestFiles()
        }
        // There should be no failed tests
        failedThresholds {
          unstable(0)
          unstableNew(0)
          failure(0)
          failureNew(0)
        }
        // Skipped tests are OK
        skippedThresholds {
          unstable(50)
          unstableNew(50)
          failure(50)
          failureNew(50)
        }
        thresholdMode(ThresholdMode.PERCENT)
      }
    }

    steps {
      // TODO: delete this when obsolete
      environmentVariables {
        env(
          'BUILD_ENVIRONMENT',
          "${buildEnvironment}",
        )
      }

      DockerUtil.shell context: delegate,
              cudaVersion: cudaVersion,
              image: dockerImage('${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_TAG}'),
              workspaceSource: "docker",
              script: '''
set -ex

if [ "${RUN_TESTS:-true}" == "false" ]; then
  echo "Skipping tests..."
  exit 0
fi

echo "Using in-repo script"
.jenkins/test.sh

exit 0
'''
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + ciFailureEmailScript)
        }
      }
    } // job(... + "-test")
  } // buildEnvironment.contains('linux')

  // TODO: This is not enabled at the moment because the docker build does not
  // have any caching enabled, which means it takes an hour to do.  Maybe run it
  // weekly.  (Then you should be able to push to registry)
  if (buildEnvironment.contains("docker")) {

    // Right now, don't bother pushing these built Docker images; we
    // need to double check that clean-up registry-side will be able to
    // take care of it
    job("${buildBasePath}/${buildEnvironment}-build-test") {
      JobUtil.common delegate, 'docker && cpu'
      JobUtil.gitCommitFromPublicGitHub delegate, "pytorch/pytorch"

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)
      }

      steps {
        GitUtil.mergeStep(delegate)

        shell '''#!/bin/bash
set -ex

if test -x ".jenkins/docker-build-test.sh"; then
    echo "Using in-repo script"
    .jenkins/docker-build-test.sh
    exit 0
fi

# PURPOSEFULLY DO NOT PUSH THIS IMAGE.  We are not sure if
# the registry will GC them quickly enough.

# TODO: run some simple CPU tests inside the image
'''
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + ciFailureEmailScript)
        }
      }
    }
  } // if (buildEnvironment.contains("docker"))

  if (buildEnvironment.contains("macos")) {
    job("${buildBasePath}/${buildEnvironment}-build-test") {
      JobUtil.common delegate, 'osx'
      JobUtil.gitCommitFromPublicGitHub(delegate, "pytorch/pytorch")

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)
      }

      steps {
        GitUtil.mergeStep(delegate)
        MacOSUtil.sandboxShell delegate, '.jenkins/macos-build-test.sh'
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + ciFailureEmailScript)
        }
      }
    }
  } // if (buildEnvironment.contains("macos"))

  if (buildEnvironment.contains('win')) {
    job("${buildBasePath}/${buildEnvironment}-build") {
      JobUtil.common delegate, 'windows && cpu'
      JobUtil.gitCommitFromPublicGitHub delegate, "pytorch/pytorch"

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)

        stringParam(
          'IMAGE_COMMIT_ID',
          '',
          "Identifier for built torch package"
        )
      }

      steps {
        GitUtil.mergeStep(delegate)

        // TODO: delete usage of this envvar in .jenkins/win-build.sh and delete it
        environmentVariables {
          env(
            'BUILD_ENVIRONMENT',
            "${buildEnvironment}",
          )
        }

        WindowsUtil.shell delegate, '.jenkins/win-build.sh', cudaVersion
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + ciFailureEmailScript)
        }
      }
    }

    // Windows
    job("${buildBasePath}/${buildEnvironment}-test") {
      JobUtil.common delegate, 'windows && gpu'
      JobUtil.gitCommitFromPublicGitHub(delegate, "pytorch/pytorch")
      JobUtil.timeoutAndFailAfter(delegate, 30)

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)

        stringParam(
          'IMAGE_COMMIT_ID',
          '',
          "Identifier for built torch package"
        )
      }

      steps {
        GitUtil.mergeStep(delegate)

        // TODO: delete usage of this envvar in .jenkins/win-test.sh and delete it
        environmentVariables {
          env(
            'BUILD_ENVIRONMENT',
            "${buildEnvironment}",
          )
        }

        WindowsUtil.shell delegate, ".jenkins/win-test.sh", cudaVersion
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + ciFailureEmailScript)
        }
      }
    } // job("${buildBasePath}/${buildEnvironment}-test")
  } // if (buildEnvironment.contains("win"))
} // buildEnvironments.each
