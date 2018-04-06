import ossci.DockerUtil
import ossci.GitUtil
import ossci.JobUtil
import ossci.MacOSUtil
import ossci.ParametersUtil
import ossci.PhaseJobUtil
import ossci.pytorch.Users
import ossci.caffe2.DockerImages
import ossci.caffe2.DockerVersion

def buildBasePath = 'caffe2-builds'
def uploadBasePath = 'caffe2-packages'

folder(buildBasePath) {
  description 'Jobs for all Caffe2 build environments'
}

// Caffe2 is setup to have different build environments depending
// on if you have a master build or just pull-request, which explains
// the duplication

def dockerBuildEnvironments = DockerImages.images

def macOsBuildEnvironments = [
  // Basic macOS builds
  'py2-system-macos10.13',
  'py2-brew-macos10.13',

  // iOS builds (hosted on macOS)
  // No need for py2/py3 since we don't care about Python for iOS build
  'py2-ios-macos10.13',

  // Anaconda build environments
  'conda2-macos10.13',
  // This is actually hardcoded to 3.6 inside scripts/build_anaconda.sh
  'conda3-macos10.13',
]

def windowsBuildEnvironments = [
  'py2-cuda9.0-cudnn7-windows'
]

def dockerCondaBuildEnvironments =
  DockerImages.images.findAll { it.startsWith("conda") }

// macOs conda-builds referred to by the nightly upload job
// These jobs are actually defined along with the rest of the
// macOsBuildEnvironments above
def macCondaBuildEnvironments = [
  'conda2-macos10.13',
  // This is actually hardcoded to 3.6 inside scripts/build_anaconda.sh
  'conda3-macos10.13',
]

def docEnvironment = 'py2-gcc5-ubuntu16.04'
def pytorchbotAuthId = 'd4d47d60-5aa5-4087-96d2-2baa15c22480'

// Runs on pull requests
multiJob("caffe2-pull-request") {
  JobUtil.gitHubPullRequestTrigger(delegate, "pytorch/pytorch", pytorchbotAuthId, Users)
  parameters {
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    ParametersUtil.CMAKE_ARGS(delegate)
    ParametersUtil.HYPOTHESIS_SEED(delegate)
  }
  steps {
    def gitPropertiesFile = './git.properties'

    GitUtil.mergeStep(delegate)
    GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

    environmentVariables {
      propertiesFile(gitPropertiesFile)
    }

    phase("Build") {
      def buildAndTestEnvironments = [
        'py2-cuda8.0-cudnn6-ubuntu16.04',
        'py2-cuda9.0-cudnn7-ubuntu16.04',
        'py2-mkl-ubuntu16.04',

        // Vanilla Ubuntu 16.04 (Python 2/3)
        'py2-gcc5-ubuntu16.04',
        //'py3-gcc5-ubuntu16.04',

        // Vanilla Ubuntu 14.04
        'py2-gcc4.8-ubuntu14.04',

        // Builds for Anaconda
        //'conda2-ubuntu16.04',
        //'conda3-ubuntu16.04',
      ]

      def buildOnlyEnvironments = [
        // Compatibility check for CUDA 8 / cuDNN 7 (build only)
        'py2-cuda8.0-cudnn7-ubuntu16.04',
        'py2-cuda8.0-cudnn5-ubuntu16.04',

        // Compiler compatibility for 14.04 (build only)
        'py2-gcc4.9-ubuntu14.04',

        // Compiler compatibility for 16.04 (build only)
        'py2-clang3.8-ubuntu16.04',
        'py2-clang3.9-ubuntu16.04',
        'py2-gcc6-ubuntu16.04',
        'py2-gcc7-ubuntu16.04',

        // Build for Android
        'py2-android-ubuntu16.04',

        // Build for iOS
        'py2-ios-macos10.13',

        // macOS builds
        'py2-system-macos10.13',

        // Windows builds
        'py2-cuda9.0-cudnn7-windows',

        // Builds for Anaconda
        'conda2-ubuntu16.04',
        'conda3-ubuntu16.04',
        'conda2-macos10.13',
        'conda3-cuda9.0-cudnn7-ubuntu16.04',

        // Run a CentOS build (verifies compatibility with CMake 2.8.12)
        'py2-cuda9.0-cudnn7-centos7',
      ]

      def definePhaseJob = { name ->
        phaseJob("${buildBasePath}/${name}") {
          parameters {
            // Pass parameters of this job
            currentBuild()
            // See https://github.com/jenkinsci/ghprb-plugin/issues/591
            predefinedProp('ghprbCredentialsId', pytorchbotAuthId)
            // Ensure consistent merge behavior in downstream builds.
            propertiesFile(gitPropertiesFile)
          }
          PhaseJobUtil.condition(delegate, '(${CAFFE2_CHANGED} == 1)')
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

def masterBuildAndTestEnvironments = [
  'py2-cuda8.0-cudnn6-ubuntu16.04',
  'py2-cuda9.0-cudnn7-ubuntu16.04',
  'py2-mkl-ubuntu16.04',

  // Vanilla Ubuntu 16.04 (Python 2/3)
  'py2-gcc5-ubuntu16.04',
  //'py3-gcc5-ubuntu16.04',

  // Vanilla Ubuntu 14.04
  'py2-gcc4.8-ubuntu14.04',
]

def masterBuildOnlyEnvironments = [
  // Compatibility check for CUDA 8 / cuDNN 7 (build only)
  'py2-cuda8.0-cudnn7-ubuntu16.04',
  'py2-cuda8.0-cudnn5-ubuntu16.04',

  // Compiler compatibility for 14.04 (build only)
  'py2-gcc4.9-ubuntu14.04',

  // Compiler compatibility for 16.04 (build only)
  'py2-clang3.8-ubuntu16.04',
  'py2-clang3.9-ubuntu16.04',
  'py2-gcc6-ubuntu16.04',
  'py2-gcc7-ubuntu16.04',

  // Build for Android
  'py2-android-ubuntu16.04',

  // Build for iOS
  'py2-ios-macos10.13',

  // macOS builds
  'py2-system-macos10.13',

  // Run a CentOS build (verifies compatibility with CMake 2.8.12)
  'py2-cuda9.0-cudnn7-centos7',

  // windows build
  "py2-cuda9.0-cudnn7-windows",
]

// Runs on release build on master
multiJob("caffe2-master") {
  JobUtil.masterTrigger(delegate, "pytorch/pytorch")

  parameters {
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)

    // The CUDA master builds should be usable with any GPU generation.
    // Not just Maxwell (which is the default for these builds).

    ParametersUtil.CMAKE_ARGS(delegate, '-DCUDA_ARCH_NAME=All')
    ParametersUtil.HYPOTHESIS_SEED(delegate)
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

      masterBuildAndTestEnvironments.each {
        definePhaseJob(it + "-trigger-test")
      }

      masterBuildOnlyEnvironments.each {
        definePhaseJob(it + "-trigger-build")
      }
    }
  }
}

// Runs on debug build on master (triggered nightly)
multiJob("caffe2-master-debug") {
  JobUtil.masterTrigger(delegate, "pytorch/pytorch", false)

  triggers {
    cron('@daily')
  }

  parameters {
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    ParametersUtil.CMAKE_ARGS(delegate, '-DCMAKE_BUILD_TYPE=DEBUG')
    ParametersUtil.HYPOTHESIS_SEED(delegate)
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

      masterBuildAndTestEnvironments.each {
        definePhaseJob(it + "-trigger-test")
      }

      masterBuildOnlyEnvironments.each {
        definePhaseJob(it + "-trigger-build")
      }
    }
  }
}

// Runs doc build on master (triggered nightly)
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
dockerBuildEnvironments.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  // Every build environment has its own Docker image
  def dockerImage = { tag ->
    return "registry.pytorch.org/caffe2/${buildEnvironment}:${tag}"
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
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)

        ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
        ParametersUtil.CMAKE_ARGS(delegate)

        if (runTests) {
          ParametersUtil.HYPOTHESIS_SEED(delegate)
        }
      }

      steps {
        def gitPropertiesFile = './git.properties'

        // GitUtil.mergeStep(delegate)

        // This is duplicated from the pull request trigger job such that
        // you don't need a pull request trigger job to test any branch
        // after merging it into any other branch (not just origin/master).
        // GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

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
              predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
              predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
              predefinedProp('DOCKER_COMMIT_TAG', builtImageTag)
            }
          }
        }
        if (runTests) {
          phase("Test") {
            phaseJob("${buildBasePath}/${buildEnvironment}-test") {
              parameters {
                currentBuild()
                predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
                predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
                predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
              }
            }
          }
        }
      }

      publishers {
        groovyPostBuild '''
def summary = manager.createSummary('terminal.png')
def buildEnvironment = manager.getEnvVariable('BUILD_ENVIRONMENT')
def builtImageTag = manager.getEnvVariable('BUILT_IMAGE_TAG')
summary.appendText(""\"
Run container with: <code>docker run -i -t registry.pytorch.org/caffe2/${buildEnvironment}:${builtImageTag} bash</code>
""\", false)
'''
      }
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
rm -rf caffe2_source || true
git clone https://github.com/caffe2/caffe2 caffe2_source
pushd caffe2_source

# Reinitialize submodules
git submodule update --init --recursive

# Ensure jenkins can write to the ccache root dir.
sudo chown jenkins:jenkins "${HOME}/.ccache"

# Make our build directory
mkdir -p build

# Make ccache log to the workspace, so we can archive it after the build
ccache -o log_file=$PWD/build/ccache.log

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
rm -rf caffe2_source || true

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

  job("${buildBasePath}/${buildEnvironment}-build") {
    JobUtil.common(delegate, buildEnvironment.contains('cuda') ? 'docker && ((cpu && ccache) || cpu_ccache)' : 'docker && cpu')
    JobUtil.gitCommitFromPublicGitHub(delegate, "pytorch/pytorch")

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)

      stringParam(
        'DOCKER_COMMIT_TAG',
        '${DOCKER_IMAGE_TAG}-adhoc-${BUILD_ID}',
        "Tag of the Docker image to commit and push upon completion " +
          "(${buildEnvironment}:DOCKER_COMMIT_TAG)",
      )

      ParametersUtil.CMAKE_ARGS(delegate)
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
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              commitImage: dockerImage('${DOCKER_COMMIT_TAG}'),
              registryCredentials: ['${USERNAME}', '${PASSWORD}'],
              // TODO: use 'host-copy'. Make sure you copy out the archived artifacts
              workspaceSource: "host-mount",
              script: '''
set -ex

# Need to checkout fetch PRs for onnxbot tracking PRs
git submodule update --init third_party/onnx || true
cd third_party/onnx && git fetch --tags --progress origin +refs/pull/*:refs/remotes/origin/pr/* && cd -

# Reinitialize submodules
git submodule update --init --recursive

# Ensure jenkins can write to the ccache root dir.
sudo chown jenkins:jenkins "${HOME}/.ccache"

# Make ccache log to the workspace, so we can archive it after the build
mkdir -p build
ccache -o log_file=$PWD/build/ccache.log

# Configure additional cmake arguments
cmake_args="$CMAKE_ARGS"

# conda must be added to the path for Anaconda builds (this location must be
# the same as that in install_anaconda.sh used to build the docker image)
if [[ "${BUILD_ENVIRONMENT}" == conda* ]]; then
  export PATH=/opt/conda/bin:$PATH
  sudo chown -R jenkins:jenkins '/opt/conda'
fi

# Build
if test -x ".jenkins/caffe2/build.sh"; then
  ./.jenkins/caffe2/build.sh ${cmake_args}
else
  ./.jenkins/build.sh ${cmake_args}
fi

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

  job("${buildBasePath}/${buildEnvironment}-test") {
    // Run tests on GPU machine if built with CUDA support
    JobUtil.common(delegate, buildEnvironment.contains('cuda') ? 'docker && gpu' : 'docker && cpu')
    JobUtil.timeoutAndFailAfter(delegate, 45)
    JobUtil.gitCommitFromPublicGitHub(delegate, "pytorch/pytorch")

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.HYPOTHESIS_SEED(delegate)
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
              // TODO: use 'docker'. Make sure you copy out the test result XML
              // to the right place
              workspaceSource: "host-mount",
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

# Build
if test -x ".jenkins/caffe2/test.sh"; then
  ./.jenkins/caffe2/test.sh
else
  ./.jenkins/test.sh
fi

# Remove benign core dumps.
# These are tests for signal handling (including SIGABRT).
rm -f ./crash/core.fatal_signal_as.*
rm -f ./crash/core.logging_test.*
'''
    }

    publishers {
      archiveArtifacts {
        allowEmpty()
        pattern('crash/*')
      }
      archiveXUnit {
        googleTest {
          pattern('test/cpp/*.xml')
          skipNoTestFiles()
        }
        jUnit {
          pattern('test/python/*.xml')
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
}

// One job per build environment
macOsBuildEnvironments.each {
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
      JobUtil.gitCommitFromPublicGitHub(delegate, "pytorch/pytorch")
      JobUtil.subJobDownstreamCommitStatus(delegate, gitHubName)

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)

        ParametersUtil.CMAKE_ARGS(delegate)
      }

      steps {
        def gitPropertiesFile = './git.properties'

        GitUtil.mergeStep(delegate)

        // This is duplicated from the pull request trigger job such that
        // you don't need a pull request trigger job to test any branch
        // after merging it into any other branch (not just origin/master).
        GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

        phase("Build") {
          phaseJob("${buildBasePath}/${buildEnvironment}-build") {
            parameters {
              currentBuild()
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
  // For Anaconda build jobs, this actually creates two separate jenkins jobs
  // We want one in caffe2-builds with a '-build' suffix and another one in
  // caffe2-packages with a '-build-upload' suffix
  def condaUploadBuild = [false]
  if (buildEnvironment.contains('conda')) {
    condaUploadBuild.push(true)
  }

  condaUploadBuild.each {
    def makeACondaUploadBuild = it

    // Put conda-package upload builds with the rest of the conda builds
    def _buildBasePath = "${buildBasePath}"
    def _buildSuffix = "build"
    if (makeACondaUploadBuild) {
      _buildBasePath = "${uploadBasePath}"
      _buildSuffix = "build-upload"
    }

    job("${_buildBasePath}/${buildEnvironment}-${_buildSuffix}") {
      JobUtil.common(delegate, 'osx')
      JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/pytorch')

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)

        if (makeACondaUploadBuild) {
          ParametersUtil.UPLOAD_TO_CONDA(delegate)
        }
      }

      wrappers {
        credentialsBinding {
          usernamePassword('ANACONDA_USERNAME', 'CAFFE2_ANACONDA_ORG_ACCESS_TOKEN', 'caffe2_anaconda_org_access_token')
        }
      }

      steps {
        GitUtil.mergeStep(delegate)

        if (buildEnvironment.contains('ios')) {
          environmentVariables {
            env(
              'BUILD_IOS',
              "1",
            )
          }
        }

        // Read the python or conda version
        if (buildEnvironment ==~ /^py[0-9].*/) {
          def pyVersion = buildEnvironment =~ /^py([0-9])/
          environmentVariables {
            env(
              'PYTHON_VERSION',
              pyVersion[0][1],
            )
          }
        } else {
          def condaVersion = buildEnvironment =~ /^conda([0-9])/
          environmentVariables {
            env('ANACONDA_VERSION', condaVersion[0][1])
            env('CAFFE2_USE_ANACONDA', 1)
            if (!makeACondaUploadBuild) {
              env('SKIP_CONDA_TESTS', 1)
            }
          }
        }

        if (buildEnvironment ==~ /^py[0-9]-brew-.*/) {
          // Use Homebrew Python
          environmentVariables {
            env(
              'PYTHON_INSTALLATION',
              'homebrew',
            )
          }
        } else {
          // Fall back to system Python
          environmentVariables {
            env(
              'PYTHON_INSTALLATION',
              'system',
            )
          }
        }

        MacOSUtil.sandboxShell delegate, '''
set -ex

# Need to checkout fetch PRs for onnxbot tracking PRs
git submodule update --init third_party/onnx || true
cd third_party/onnx && git fetch --tags --progress origin +refs/pull/*:refs/remotes/origin/pr/* && cd -

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
  curl -o ${TMPDIR}/anaconda.sh "https://repo.continuum.io/archive/Anaconda${ANACONDA_VERSION}-5.0.1-MacOSX-x86_64.sh"
  /bin/bash ${TMPDIR}/anaconda.sh -b -p ${TMPDIR}/anaconda
  rm -f ${TMPDIR}/anaconda.sh
  export PATH="${TMPDIR}/anaconda/bin:${PATH}"
  source ${TMPDIR}/anaconda/bin/activate
fi

# Build
if [ "${BUILD_IOS:-0}" -eq 1 ]; then
  scripts/build_ios.sh
elif [ -n "${CAFFE2_USE_ANACONDA}" ]; then
  # Please don't make any changes to the conda-build process here. Instead, edit
  # scripts/build_anaconda.sh since conda docker builds in caffe2-builds also
  # use that script
  scripts/build_anaconda.sh
else
  scripts/build_local.sh
fi

'''
      }
    }
  }
}

windowsBuildEnvironments.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  // Windows trigger jobs
  // TODO this is a verbatim copy paste from another trigger job in this file.
  // These should be consolidated
  def jobName = "${buildBasePath}/${buildEnvironment}-trigger-build"
  def gitHubName = "caffe2-${buildEnvironment}-build"

  multiJob(jobName) {
    JobUtil.commonTrigger(delegate)
    JobUtil.gitCommitFromPublicGitHub(delegate, "pytorch/pytorch")
    JobUtil.subJobDownstreamCommitStatus(delegate, gitHubName)

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.CMAKE_ARGS(delegate)
    }

    steps {
      def gitPropertiesFile = './git.properties'
      GitUtil.mergeStep(delegate)
      GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

      phase("Build") {
        phaseJob("${buildBasePath}/${buildEnvironment}-build") {
          parameters {
            currentBuild()
            propertiesFile(gitPropertiesFile)
          }
        }
      }
    }
  } // Actual definition of Windows trigger multijob

  // Windows build jobs
  job("${buildBasePath}/${buildEnvironment}-build") {
    JobUtil.common(delegate, 'windows && cpu')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/pytorch')
    JobUtil.timeoutAndFailAfter(delegate, 40)

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
    }

    steps {
      GitUtil.mergeStep(delegate)
      // TODO use WindowsUtil
      shell('''
git submodule update --init
export PATH="$PATH:/c/Program Files/CMake/bin:"
./scripts/build_windows.bat
''')
    }
  } // Windows build jobs
} // Windows jobs, just build for now


//
// Following definitions and jobs build using conda-build and then upload the
// packages to Anaconda.org
//
folder(uploadBasePath) {
  description 'Jobs for nightly uploads of Caffe2 packages'
}

dockerCondaBuildEnvironments.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  // Every build environment has its own Docker image
  def dockerImage = { tag ->
    return "registry.pytorch.org/caffe2/${buildEnvironment}:${tag}"
  }

  job("${uploadBasePath}/${buildEnvironment}-build-upload") {
    JobUtil.common(delegate, buildEnvironment.contains('cuda') ? 'docker && gpu' : 'docker && cpu')
    JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/pytorch')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)
      ParametersUtil.UPLOAD_TO_CONDA(delegate)
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    }

    wrappers {
      credentialsBinding {
        usernamePassword('USERNAME', 'PASSWORD', 'nexus-jenkins')
        usernamePassword('ANACONDA_USERNAME', 'CAFFE2_ANACONDA_ORG_ACCESS_TOKEN', 'caffe2_anaconda_org_access_token')
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
        // Populate CUDA and cuDNN versions for conda to label packages with
        def caffe2CudaVersion = buildEnvironment =~ /cuda(\d.\d)/
        def caffe2CudnnVersion = buildEnvironment =~ /cudnn(\d)/
        environmentVariables {
          env('CAFFE2_CUDA_VERSION', caffe2CudaVersion[0][1])
          env('CAFFE2_CUDNN_VERSION', caffe2CudnnVersion[0][1])
        }
      }

      DockerUtil.shell context: delegate,
              image: dockerImage('${DOCKER_IMAGE_TAG}'),
              registryCredentials: ['${USERNAME}', '${PASSWORD}'],
              cudaVersion: cudaVersion,
              // TODO: use 'docker'. Make sure you copy out the test result XML
              // to the right place
              workspaceSource: "host-mount",
              script: '''
set -ex
git submodule update --init --recursive
# Please don't make any changes to the conda-build process here. Instead, edit
# scripts/build_anaconda.sh since conda docker builds in caffe2-builds also
# use that script
PATH=/opt/conda/bin:$PATH ./scripts/build_anaconda.sh
'''
    }
  }
}

// Nightly job to upload conda packages. This job just triggers the above builds
// every night with UPLOAD_TO_CONDA set to 1
multiJob("nightly-conda-package-upload") {
  JobUtil.commonTrigger(delegate)
  JobUtil.gitCommitFromPublicGitHub(delegate, 'pytorch/pytorch')
  parameters {
    ParametersUtil.GIT_COMMIT(delegate)
    ParametersUtil.GIT_MERGE_TARGET(delegate)
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    ParametersUtil.CMAKE_ARGS(delegate, '-DCUDA_ARCH_NAME=ALL')
    ParametersUtil.UPLOAD_TO_CONDA(delegate, true)
  }
  triggers {
    cron('@daily')
  }
  steps {
    def gitPropertiesFile = './git.properties'
    GitUtil.mergeStep(delegate)
    GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

    phase("Build") {
      def definePhaseJob = { basePath, name ->
        phaseJob("${basePath}/${name}-build-upload") {
          parameters {
            // Pass parameters of this job
            currentBuild()
            // Ensure consistent merge behavior in downstream builds.
            propertiesFile(gitPropertiesFile)
          }
        }
      }

      macCondaBuildEnvironments.each {
        definePhaseJob(buildBasePath, it)
      }

      dockerCondaBuildEnvironments.each {
        definePhaseJob(uploadBasePath, it)
      }
    }
  }
}
