import ossci.DockerUtil
import ossci.GitUtil
import ossci.JobUtil
import ossci.ParametersUtil
import ossci.detectron.Users

def defaultValue = '1'
def buildBasePath = 'detectron-builds'

folder(buildBasePath) {
  description 'Jobs for all Detectron build environments'
}

def dockerBuildEnvironments = [
  // Primary environments (Python 2)
  'py2-cuda8.0-cudnn6-ubuntu16.04',
  'py2-cuda8.0-cudnn7-ubuntu16.04',
  'py2-cuda9.0-cudnn7-ubuntu16.04',
]

// Runs on pull requests
multiJob('detectron-pull-request') {
  JobUtil.gitHubPullRequestTrigger(delegate, 'facebookresearch/detectron', 'e8c3034a-549f-432f-b811-ec4bbc4b3d62', Users)

  parameters {
    // This defaults to ${ghprbActualCommit} so that the Git SCM plugin
    // will check out the HEAD of the pull request. Another parameter emitted
    // by the GitHub pull request builder plugin is ${sha1}, which points to
    // origin/pulls/1234/merge if and only if the PR could be merged to master.
    // This ref changes whenever anything is committed to master.
    // We use a multi job which triggers builds at different times.
    // Following this ref blindly can therefore lead to a single build
    // using different commits. To avoid this, we use ${ghprbActualCommit},
    // perform the merge to master ourselves, and capture the commit hash of
    // origin/master in this build. The raw version of this commit hash (and
    // not origin/master) is then propagated to downstream builds.
    ParametersUtil.GIT_COMMIT(delegate, '${ghprbActualCommit}')
    ParametersUtil.GIT_MERGE_TARGET(delegate, 'origin/${ghprbTargetBranch}')
  }

  steps {
    def gitPropertiesFile = './git.properties'

    GitUtil.mergeStep(delegate)
    GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

    phase("Build and test") {
      def buildAndTestEnvironments = [
        'py2-cuda8.0-cudnn6-ubuntu16.04',
        'py2-cuda8.0-cudnn7-ubuntu16.04',
        'py2-cuda9.0-cudnn7-ubuntu16.04',
      ]

      def buildOnlyEnvironments = [
      ]

      def definePhaseJob = { name ->
        phaseJob("${buildBasePath}/${name}") {
          parameters {
            // Pass parameters of this job
            currentBuild()
            // See https://github.com/jenkinsci/ghprb-plugin/issues/591
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
}

// One job per build environment
dockerBuildEnvironments.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  // Every build environment has its own Docker image
  def dockerImage = { tag ->
    return "registry.pytorch.org/detectron/${buildEnvironment}:${tag}"
  }

  def jobName = "${buildBasePath}/${buildEnvironment}-trigger-test"
  def gitHubName = "${buildEnvironment}"
  multiJob(jobName) {
    JobUtil.commonTrigger(delegate)
    JobUtil.subJobDownstreamCommitStatus(delegate, gitHubName)

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
    }

    steps {
      def builtImageTag = '${DOCKER_IMAGE_TAG}-build-test-${BUILD_ID}'

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
            predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
            predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
            predefinedProp('DOCKER_COMMIT_TAG', builtImageTag)
          }
        }
      }
      // phase("Test") {
      //   phaseJob("${buildBasePath}/${buildEnvironment}-test") {
      //     parameters {
      //       currentBuild()
      //       propertiesFile(gitPropertiesFile)
      //       predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
      //     }
      //   }
      // }
    }

    publishers {
      groovyPostBuild '''
def summary = manager.createSummary('terminal.png')
def buildEnvironment = manager.getEnvVariable('BUILD_ENVIRONMENT')
def builtImageTag = manager.getEnvVariable('BUILT_IMAGE_TAG')
summary.appendText(""\"
Run container with: <code>docker run -i -t registry.pytorch.org/detectron/${buildEnvironment}:${builtImageTag} bash</code>
""\", false)
'''
    }
  }

  job("${buildBasePath}/${buildEnvironment}-build") {
    JobUtil.common(delegate, 'docker && cpu && ccache')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'facebookresearch/detectron')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      ParametersUtil.DOCKER_IMAGE_TAG(delegate, defaultValue)

      stringParam(
        'DOCKER_COMMIT_TAG',
        '${DOCKER_IMAGE_TAG}-adhoc-${BUILD_ID}',
        "Tag of the Docker image to commit and push upon completion " +
          "(${buildEnvironment}:DOCKER_COMMIT_TAG)",
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
        env(
          'BUILD_ENVIRONMENT',
          "${buildEnvironment}",
        )
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              commitImage: dockerImage('${DOCKER_COMMIT_TAG}'),
              registryCredentials: ['${USERNAME}', '${PASSWORD}'],
              workspaceSource: "host-copy",
              script: '''
set -ex

# Set up Caffe2
mv /usr/local/caffe2 /usr/local/caffe2_build
export Caffe2_DIR="/usr/local/caffe2_build"

export PYTHONPATH="/usr/local/caffe2_build:${PYTHONPATH}"
export LD_LIBRARY_PATH="/usr/local/caffe2_build/lib:${LD_LIBRARY_PATH}"

# Install Python dependencies
PY_DEPS=(
  "numpy>=1.13"
  "pyyaml>=3.12"
  "matplotlib"
  "opencv-python>=3.2"
  "setuptools"
  "Cython"
  "mock"
  "scipy"
)
pip install "${PY_DEPS[@]}" --user

# Install the COCO API
git clone https://github.com/cocodataset/cocoapi.git "${HOME}/cocoapi"
pushd "${HOME}/cocoapi/PythonAPI" && python2 setup.py install --user && popd

# Set up Python modules
cd lib && make && cd ..

# Build custom ops
cd lib && make ops && cd ..
'''
    }

    publishers {
      archiveArtifacts {
        allowEmpty()
        pattern('crash/*')
      }
    }
  }
}
