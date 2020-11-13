import ossci.DockerUtil
import ossci.EmailUtil
import ossci.GitUtil
import ossci.JobUtil
import ossci.MacOSUtil
import ossci.ParametersUtil
import ossci.PhaseJobUtil
import ossci.pytorch.Users
import ossci.caffe2.Images
import ossci.caffe2.DockerVersion

def buildBasePath = 'caffe2-builds'

folder(buildBasePath) {
  description 'Jobs for all Caffe2 build environments'
}
def pytorchbotAuthId = 'd4d47d60-5aa5-4087-96d2-2baa15c22480'

// trigger jobs are in pytorch.groovy

// Runs doc build on master (triggered nightly)
def docEnvironment = 'py2-gcc5-ubuntu16.04'
multiJob("caffe2-master-doc") {
  JobUtil.commonTrigger(delegate)

  parameters {
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    booleanParam(
      'DOC_PUSH',
      true,
      'Whether to doc push or not',
    )
  }

  scm {
    git {
      remote {
        github('caffe2/caffe2.github.io')
        refspec('+refs/heads/master:refs/remotes/origin/master')
      }
      branch('origin/master')
      GitUtil.defaultExtensions(delegate)
    }
  }

  triggers {
    cron('@daily')
  }

  steps {
    phase("Build and Push") {
      phaseJob("${buildBasePath}/${docEnvironment}-doc") {
        parameters {
          predefinedProp('DOCKER_IMAGE_TAG', '${DOCKER_IMAGE_TAG}')
          predefinedProp('DOC_PUSH', '${DOC_PUSH}')
        }
      }
    }
  }
}

// One job per build environment
Images.allDockerBuildEnvironments.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it
  def dockerBaseImage = Images.baseImageOf[(buildEnvironment)]

  // Every build environment has its own Docker image
  def dockerImage = { tag ->
    if (buildEnvironment.contains("rocm")) {
      return "308535385114.dkr.ecr.us-east-1.amazonaws.com/pytorch/${dockerBaseImage}:${tag}"
    }
    return "308535385114.dkr.ecr.us-east-1.amazonaws.com/caffe2/${dockerBaseImage}:${tag}"
  }

  // Create triggers for build-only and build-and-test.
  // The build only trigger is used for build environments where we
  // only care the code compiles and assume test coverage is provided
  // by other builds (for example: different compilers).
  [false, true].each {
    def runTests = it

    def jobName = "${buildBasePath}/${buildEnvironment}-trigger"
    def gitHubName = "caffe2-${buildEnvironment}"
    if (!runTests) {
      jobName += "-build"
      gitHubName += "-build"
    } else {
      jobName += "-test"
      gitHubName += "-test"
    }

    multiJob(jobName) {
      JobUtil.commonTrigger(delegate)
      JobUtil.subJobDownstreamCommitStatus(delegate, gitHubName)

      parameters {
        ParametersUtil.IN_CI(delegate)
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)

        ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
        ParametersUtil.CMAKE_ARGS(delegate)

        ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')

        booleanParam(
          'RUN_TESTS',
          true,
          'Whether to run tests or not',
        )
        booleanParam(
          'RUN_BENCH',
          false,
          'Whether to run benchmarks or not',
        )

        if (runTests) {
          ParametersUtil.HYPOTHESIS_SEED(delegate)
        }
      }

      wrappers {
        // This is needed so that Jenkins knows to hide these strings in all the console outputs
        credentialsBinding {
          usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
        }
      }

      steps {
        def gitPropertiesFile = './git.properties'

        // This is duplicated from the pull request trigger job such that
        // you don't need a pull request trigger job to test any branch
        // after merging it into any other branch (not just origin/master).
        // GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

        // Different triggers (build and run tests or build only)
        // means we have to use different tags, or we risk conflicting
        // prefixes (rare, but possible).
        def builtImagePrefix = ''
        if (runTests) {
          builtImageTag = 'tmp-${DOCKER_IMAGE_TAG}-build-test-${GIT_COMMIT}'
        } else {
          builtImageTag = 'tmp-${DOCKER_IMAGE_TAG}-build-${GIT_COMMIT}'
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
              predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
              predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
              predefinedProp('DOCKER_COMMIT_TAG', builtImageTag)
              predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
            }
          }
        }
        if (runTests) {
          phase("Run") {
            phaseJob("${buildBasePath}/${buildEnvironment}-test") {
              parameters {
                currentBuild()
                predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
                predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
                predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
                predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
              }
              PhaseJobUtil.condition(delegate, '${RUN_TESTS}')
            }
            phaseJob("${buildBasePath}/${buildEnvironment}-bench") {
              parameters {
                currentBuild()
                predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
                predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
                predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
                predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
              }
              PhaseJobUtil.condition(delegate, '${RUN_BENCH}')
            }
          }
        }
      }

// This does not work since moving to Amazon ECR...
//       publishers {
//         groovyPostBuild '''
// def summary = manager.createSummary('terminal.png')
// def buildEnvironment = manager.getEnvVariable('BUILD_ENVIRONMENT')
// def builtImageTag = manager.getEnvVariable('BUILT_IMAGE_TAG')
// summary.appendText(""\"
// Run container with: <code>docker run -i -t registry.pytorch.org/caffe2/${buildEnvironment}:${builtImageTag} bash</code>
// ""\", false)
// '''
//       }
    }
  }

  if (buildEnvironment == docEnvironment) {
    job("${buildBasePath}/${buildEnvironment}-doc") {
      JobUtil.common(delegate, "docker && cpu")

      parameters {
        // TODO: Accept GIT_COMMIT, eliminate race, small profit
        // GitUtil.GIT_COMMIT(delegate)
        // GitUtil.GIT_MERGE_TARGET(delegate)

        ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
        booleanParam(
          'DOC_PUSH',
          false,
          'Whether to doc push or not',
        )
      }

      // Fetch caffe2.github.io as SSH so we can push
      scm {
        git {
          remote {
            github('caffe2/caffe2.github.io', 'ssh')
            credentials('caffe2bot')
          }
          branch('origin/master')
          GitUtil.defaultExtensions(delegate)
        }
      }

      wrappers {
        // This is needed so that Jenkins knows to hide these strings in all the console outputs
        credentialsBinding {
          usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
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
                image: dockerImage('${DOCKER_IMAGE_TAG}'),
                workspaceSource: "host-mount",
                script: '''
set -ex

# Install documentation dependencies temporarily within this container
sudo apt-get update
sudo apt-get install -y doxygen graphviz

# Create folder to transfer docs
temp_dir=$(mktemp -d)
trap "rm -rf ${temp_dir}" EXIT

# Get all the documentation sources, put them in one place
rm -rf pytorch_source || true
git clone https://github.com/pytorch/pytorch pytorch_source
pushd pytorch_source

# Reinitialize submodules
git submodule update --init --recursive

# Do not need to go into the caffe2 directory within the PyTorch 
# repo since the root CMakeLists.txt handles the process of making 
# caffe2 and its docs. So do the below from the pytorch repo root.

# Make our build directory
mkdir -p build

# Build doxygen docs
cd build
time cmake -DBUILD_DOCS=ON .. && make
cd ..

# Move docs to the temp folder
mv build/docs/doxygen-c "${temp_dir}"
mv build/docs/doxygen-python "${temp_dir}"

# Generate operator catalog in the temp folder
export PYTHONPATH="build:$PYTHONPATH"
python caffe2/python/docs/github.py "${temp_dir}/operators-catalogue.md"

# Go up a level for the doc push
popd

# Remove source directory
rm -rf pytorch_source || true

# Copy docs from the temp folder and git add
git rm -rf doxygen-c || true
git rm -rf doxygen-python || true
mv "${temp_dir}/operators-catalogue.md" _docs/
mv "${temp_dir}/doxygen-c" .
mv "${temp_dir}/doxygen-python" .
git add _docs/operators-catalogue.md || true
git add -A doxygen-c || true
git add -A doxygen-python || true
git status

if [ "${DOC_PUSH:-true}" == "false" ]; then
  echo "Skipping doc push..."
  exit 0
fi

# If there aren't changes, don't make a commit; push is no-op
git config user.email "jenkins@ci.pytorch.org"
git config user.name "Jenkins"
git commit -m "Auto-generating doxygen and operator docs" || true
git status
'''
      }
      publishers {
        git {
          pushOnlyIfSuccess()
          branch('origin', 'master')
        }
      }
    }
  }

  // All docker builds
  job("${buildBasePath}/${buildEnvironment}-build") {
    if (buildEnvironment.contains('rocm')) {
      JobUtil.common(delegate, 'docker && bigcpu')
    } else {
      JobUtil.common(delegate, 'docker && cpu')
    }
    JobUtil.gitCommitFromPublicGitHub(delegate, '${GITHUB_REPO}')

    parameters {
      ParametersUtil.IN_CI(delegate)
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)

      ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')

      stringParam(
        'DOCKER_COMMIT_TAG',
        'tmp-${DOCKER_IMAGE_TAG}-adhoc-${GIT_COMMIT}',
        "Tag of the Docker image to commit and push upon completion " +
          "(${buildEnvironment}:DOCKER_COMMIT_TAG)",
      )

      ParametersUtil.CMAKE_ARGS(delegate)
    }

    wrappers {
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      credentialsBinding {
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
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
        if (Images.integratedEnvironments.contains(buildEnvironment)) {
          env('INTEGRATED', 1)
        }
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              commitImage: dockerImage('${DOCKER_COMMIT_TAG}'),
              // TODO: use 'host-copy'. Make sure you copy out the archived artifacts
              workspaceSource: "host-copy",
              script: '''
set -ex

# Reinitialize submodules
git submodule update --init --recursive

# Configure additional cmake arguments
cmake_args=()
cmake_args+=("$CMAKE_ARGS")

if [[ $BUILD_ENVIRONMENT == *aten* ]]; then
  cmake_args+=("-DBUILD_ATEN=ON")
fi

# conda must be added to the path for Anaconda builds (this location must be
# the same as that in install_anaconda.sh used to build the docker image)
if [[ "${BUILD_ENVIRONMENT}" == conda* ]]; then
  export PATH=/opt/conda/bin:$PATH
  sudo chown -R jenkins:jenkins '/opt/conda'
fi

# Build
./.jenkins/caffe2/build.sh ${cmake_args[@]}

# Show sccache stats if it is running
if pgrep sccache > /dev/null; then
  sccache --show-stats
fi
'''
    }

    publishers {
      // Experiment: disable this, see if anyone notices
      //archiveArtifacts {
      //  allowEmpty()
      //  pattern('crash/*')
      //}
      archiveArtifacts {
        allowEmpty()
        pattern('build*/CMakeCache.txt')
      }
      archiveArtifacts {
        allowEmpty()
        pattern('build*/CMakeFiles/*.log')
      }
    }
  }

  // All docker builds with tests
  job("${buildBasePath}/${buildEnvironment}-test") {
    def label = 'docker'
    if (buildEnvironment.contains('cuda')) {
       // Run tests on GPU machine if built with CUDA support
       label += ' && gpu'
    } else if (buildEnvironment.contains('rocm')) {
       label += ' && rocm'
    } else {
       label += ' && cpu'
    }
    JobUtil.common(delegate, label)
    JobUtil.timeoutAndFailAfter(delegate, 180)
    JobUtil.gitCommitFromPublicGitHub(delegate, '${GITHUB_REPO}')

    parameters {
      ParametersUtil.IN_CI(delegate)
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.HYPOTHESIS_SEED(delegate)

      ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')
    }

    wrappers {
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      credentialsBinding {
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    }

    steps {
      // test job no longer needs to clone since 'host-copy' of workspace is used
      //GitUtil.mergeStep(delegate)

      environmentVariables {
        env(
          'BUILD_ENVIRONMENT',
          "${buildEnvironment}",
        )
        if (Images.integratedEnvironments.contains(buildEnvironment)) {
          env('INTEGRATED', 1)
        }
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
              cudaVersion: cudaVersion,
              // TODO: use 'docker'. Make sure you copy out the test result XML
              // to the right place
              workspaceSource: "docker",
              script: '''
set -ex

# libdc1394 (dependency of OpenCV) expects /dev/raw1394 to exist...
sudo ln /dev/null /dev/raw1394

# Hotfix, use hypothesis 3.44.6 on Ubuntu 14.04
# See comments on https://github.com/HypothesisWorks/hypothesis-python/commit/eadd62e467d6cee6216e71b391951ec25b4f5830
if [[ "$BUILD_ENVIRONMENT" == *ubuntu14.04* ]]; then
  sudo pip uninstall -y hypothesis
  sudo pip install hypothesis==3.44.6
fi

# conda must be added to the path for Anaconda builds (this location must be
# the same as that in install_anaconda.sh used to build the docker image)
if [[ "${BUILD_ENVIRONMENT}" == conda* ]]; then
  export PATH=/opt/conda/bin:$PATH
fi

# Test
./.jenkins/caffe2/test.sh

# Remove benign core dumps.
# These are tests for signal handling (including SIGABRT).
rm -f ./crash/core.fatal_signal_as.*
rm -f ./crash/core.logging_test.*
'''
    }

    publishers {
      //archiveArtifacts {
      //  allowEmpty()
      //  pattern('crash/*')
      //}
      archiveXUnit {
        googleTest {
          pattern('caffe2_tests/cpp/*.xml')
          skipNoTestFiles()
        }
        // jUnit {
        //   pattern('caffe2_tests/junit_reports/*.xml')
        //   skipNoTestFiles()
        // }
        jUnit {
          pattern('caffe2_tests/python/*.xml')
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
  }

  job("${buildBasePath}/${buildEnvironment}-bench") {
    def label = 'docker'
    if (buildEnvironment.contains('cuda')) {
       // Run tests on GPU machine if built with CUDA support
       label += ' && gpu-bench'
    } else if (buildEnvironment.contains('rocm')) {
       label += ' && rocm-bench'
    } else {
       label += ' && cpu-bench'
    }
    JobUtil.common(delegate, label)
    JobUtil.timeoutAndFailAfter(delegate, 180)
    JobUtil.gitCommitFromPublicGitHub(delegate, '${GITHUB_REPO}')

    parameters {
      ParametersUtil.IN_CI(delegate)
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.HYPOTHESIS_SEED(delegate)

      ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')
    }

    wrappers {
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      credentialsBinding {
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    }

    steps {
      // bench job no longer needs to clone since 'host-copy' of workspace is used
      //GitUtil.mergeStep(delegate)

      environmentVariables {
        env(
          'BUILD_ENVIRONMENT',
          "${buildEnvironment}",
        )
        if (Images.integratedEnvironments.contains(buildEnvironment)) {
          env('INTEGRATED', 1)
        }
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
              cudaVersion: cudaVersion,
              // TODO: use 'docker'. Make sure you copy out the test result XML
              // to the right place
              workspaceSource: "docker",
              script: '''
set -ex

# libdc1394 (dependency of OpenCV) expects /dev/raw1394 to exist...
sudo ln /dev/null /dev/raw1394

# conda must be added to the path for Anaconda builds (this location must be
# the same as that in install_anaconda.sh used to build the docker image)
if [[ "${BUILD_ENVIRONMENT}" == conda* ]]; then
  export PATH=/opt/conda/bin:$PATH
fi

# Bench
./.jenkins/caffe2/bench.sh

# Remove benign core dumps.
# These are tests for signal handling (including SIGABRT).
rm -f ./crash/core.fatal_signal_as.*
rm -f ./crash/core.logging_test.*
'''
    }
  }
}

// One job per build environment
Images.macOsBuildEnvironments.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  // Create triggers for build, and build and test.
  // The build only trigger is used for build environments where we
  // only care the code compiles and assume test coverage is provided
  // by other builds (for example: different compilers).
  [false].each {
    def runTests = it

    def jobName = "${buildBasePath}/${buildEnvironment}-trigger"
    def gitHubName = "caffe2-${buildEnvironment}"
    if (!runTests) {
      jobName += "-build"
      gitHubName += "-build"
    } else {
      jobName += "-test"
      gitHubName += "-test"
    }

    multiJob(jobName) {
      JobUtil.commonTrigger(delegate)
      JobUtil.gitCommitFromPublicGitHub(delegate, '${GITHUB_REPO}')
      JobUtil.subJobDownstreamCommitStatus(delegate, gitHubName)

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)

        ParametersUtil.CMAKE_ARGS(delegate)
        ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')
      }

      wrappers {
        // This is needed so that Jenkins knows to hide these strings in all the console outputs
        credentialsBinding {
          usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
        }
      }

      steps {
        def gitPropertiesFile = './git.properties'

        // This is duplicated from the pull request trigger job such that
        // you don't need a pull request trigger job to test any branch
        // after merging it into any other branch (not just origin/master).
        GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

        phase("Build") {
          phaseJob("${buildBasePath}/${buildEnvironment}-build") {
            parameters {
              currentBuild()
              predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
              propertiesFile(gitPropertiesFile)
            }
          }
        }
        // Not doing any macOS testing yet
        // if (runTests) {
        //   phase("Test") {
        //     phaseJob("${buildBasePath}/${buildEnvironment}-test") {
        //       parameters {
        //         currentBuild()
        //         propertiesFile(gitPropertiesFile)
        //       }
        //     }
        //   }
        // }
      }
    }
  }

  // Define every macOs build job
  job("${buildBasePath}/${buildEnvironment}-build") {
    JobUtil.common(delegate, 'osx')
    JobUtil.gitCommitFromPublicGitHub(delegate, '${GITHUB_REPO}')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')
    }

    wrappers {
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      credentialsBinding {
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    }

    steps {
      environmentVariables {
        if (buildEnvironment.contains('ios')) {
          env('BUILD_IOS', "1")
        }
        if (Images.integratedEnvironments.contains(buildEnvironment)) {
          env('INTEGRATED', 1)
        }
        // Anaconda environment variables
        if (buildEnvironment.contains('conda')) {
          env('CAFFE2_USE_ANACONDA', 1)
          env('SKIP_CONDA_TESTS', 1)
        }

        // Homebrew or system python
        if (buildEnvironment ==~ /^py[0-9]-brew-.*/) {
          env('PYTHON_INSTALLATION', 'homebrew')
        } else {
          env('PYTHON_INSTALLATION', 'system')
        }
      }

      // Read the python or conda version
      if (buildEnvironment ==~ /^py[0-9].*/) {
        def pyVersion = buildEnvironment =~ /^py([0-9])/
        environmentVariables {
          env('PYTHON_VERSION', pyVersion[0][1])
        }
      } else {
        def condaVersion = buildEnvironment =~ /^conda([0-9])/
        environmentVariables {
          env('ANACONDA_VERSION', condaVersion[0][1])
        }
      }


      MacOSUtil.sandboxShell delegate, '''
set -ex

# Reinitialize submodules
git submodule update --init --recursive

# Reinitialize path (see man page for path_helper(8))
eval `/usr/libexec/path_helper -s`

# Fix for xcode-select in jenkins
export DEVELOPER_DIR=/Applications/Xcode9.app/Contents/Developer

# Use Homebrew Python if configured to do so
if [ "${PYTHON_INSTALLATION}" == "homebrew" ]; then
  export PATH=/usr/local/opt/python/libexec/bin:/usr/local/bin:$PATH
fi

# Install Anaconda if we need to
if [ -n "${CAFFE2_USE_ANACONDA}" ]; then
  rm -rf ${TMPDIR}/anaconda
  curl -o ${TMPDIR}/anaconda.sh https://repo.continuum.io/miniconda/Miniconda${ANACONDA_VERSION}-latest-MacOSX-x86_64.sh
  /bin/bash ${TMPDIR}/anaconda.sh -b -p ${TMPDIR}/anaconda
  rm -f ${TMPDIR}/anaconda.sh
  export PATH="${TMPDIR}/anaconda/bin:${PATH}"
  source ${TMPDIR}/anaconda/bin/activate
fi

# Build
if [ "${BUILD_IOS:-0}" -eq 1 ]; then
  scripts/build_ios.sh
elif [ -n "${CAFFE2_USE_ANACONDA}" ]; then
  # All conda build logic should be in scripts/build_anaconda.sh
  scripts/build_anaconda.sh
else
  scripts/build_local.sh
fi

'''
    }
  }
}
