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
import ossci.caffe2.DockerVersion as Caffe2DockerVersion
import ossci.caffe2.Images as Caffe2Images

def buildBasePath = 'pytorch-builds'

folder(buildBasePath) {
  description 'Jobs for all PyTorch and Caffe2 build environments'
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
  "pytorch-linux-xenial-cuda9.2-cudnn7-py3-gcc7",
  "pytorch-linux-xenial-py3-clang5-asan",
  "pytorch-linux-trusty-py2.7.9",
  "pytorch-linux-trusty-py2.7",
  "pytorch-linux-trusty-py3.5",
  "pytorch-linux-trusty-py3.6-gcc4.8",
  "pytorch-linux-trusty-py3.6-gcc5.4",
  "pytorch-linux-trusty-py3.6-gcc7",
  "pytorch-linux-trusty-pynightly",
  "pytorch-win-ws2016-cuda9-cudnn7-py3",
  "pytorch-macos-10.13-py3",
  "pytorch-macos-10.13-cuda9.2-cudnn7-py3",

  // NB: This image is taken from Caffe2
  "py2-clang3.8-rocm1.7.1-ubuntu16.04",

  // This is really expensive to run because it is a total build
  // from scratch.  Maybe we have to do it nightly.
  // "pytorch-docker",
]

def experimentalBuildEnvironments = [
]

def isRocmBuild = { buildEnvironment ->
  return buildEnvironment.contains("rocm")
}

def docAndTutorialEnvironment = "pytorch-linux-xenial-cuda8-cudnn6-py3"
def perfTestEnvironment = "pytorch-linux-xenial-cuda8-cudnn6-py3"
def splitTestEnvironments = [
  "pytorch-macos-10.13-py3",
  "pytorch-win-ws2016-cuda9-cudnn7-py3",
  "pytorch-linux-xenial-cuda8-cudnn6-py3",
  "pytorch-linux-xenial-cuda9-cudnn7-py2",
  "pytorch-linux-xenial-cuda9-cudnn7-py3",
  "pytorch-linux-xenial-cuda9.2-cudnn7-py3-gcc7",
  "pytorch-linux-xenial-py3-clang5-asan",
]
def avxConfigTestEnvironment = "pytorch-linux-xenial-cuda8-cudnn6-py3"

// Every build environment has its own Docker image
def dockerImage = { staticBuildEnv, buildEnvironment, tag, caffe2_tag ->
  if (isRocmBuild(staticBuildEnv)) {
    return "308535385114.dkr.ecr.us-east-1.amazonaws.com/caffe2/${buildEnvironment}:${caffe2_tag}"
  }
  return "308535385114.dkr.ecr.us-east-1.amazonaws.com/pytorch/${buildEnvironment}:${tag}"
}

def mailRecipients = "ezyang@fb.com pietern@fb.com willfeng@fb.com englund@fb.com"
def rocmMailRecipients = "ezyang@fb.com gains@fb.com jbai@fb.com Johannes.Dieterich@amd.com Mayank.Daga@amd.com"

def pytorchbotAuthId = 'd4d47d60-5aa5-4087-96d2-2baa15c22480'

def masterJobSettings = { context, repo, triggerOnPush, defaultCmakeArgs, commitSource, localMailRecipients ->
  context.with {
    JobUtil.masterTrigger(delegate, repo, "master", triggerOnPush)
    parameters {
      ParametersUtil.RUN_DOCKER_ONLY(delegate)
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)
      ParametersUtil.CMAKE_ARGS(delegate, defaultCmakeArgs)
      ParametersUtil.HYPOTHESIS_SEED(delegate)
    }
    wrappers {
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      credentialsBinding {
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    } // wrappers
    steps {
      def gitPropertiesFile = './git.properties'
      GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

      phase("Master jobs") {
        buildEnvironments.each {
          def buildEnvironment = it;
          phaseJob("${buildBasePath}/${it}-trigger") {
            parameters {
              // Pass parameters of this job
              currentBuild()
              // Checkout this exact same revision in downstream builds.
              gitRevision()
              propertiesFile(gitPropertiesFile)
              // Only pytorch/pytorch master gets documentation pushes
              booleanParam('DOC_PUSH', repo == "pytorch/pytorch")
              predefinedProp('COMMIT_SOURCE', commitSource)
              predefinedProp('GITHUB_REPO', repo)
            }
            if (!buildEnvironment.contains('linux')) {
              PhaseJobUtil.condition(delegate, '!${RUN_DOCKER_ONLY}')
            }
          }
        }
        def definePhaseJob = { name ->
          phaseJob("caffe2-builds/${name}") {
            parameters {
              // Checkout this exact same revision in downstream builds.
              gitRevision()
              predefinedProp('DOCKER_IMAGE_TAG', '${CAFFE2_DOCKER_IMAGE_TAG}')
              predefinedProp('GITHUB_REPO', repo)
              propertiesFile(gitPropertiesFile)
              // Pass parameters of this job
              currentBuild()
            }
          }
        }

        Caffe2Images.buildAndTestEnvironments.each {
          definePhaseJob(it + "-trigger-test")
        }

        Caffe2Images.buildOnlyEnvironments.each {
          definePhaseJob(it + "-trigger-build")
        }
      }
    }
    publishers {
      mailer(localMailRecipients, false, true)
    }
  }
}

multiJob("pytorch-master") {
  masterJobSettings(delegate, "pytorch/pytorch", true, '-DCUDA_ARCH_NAME=All', "master", mailRecipients)
}

//multiJob("rocm-pytorch-master") {
//  masterJobSettings(delegate, "ROCmSoftwarePlatform/pytorch", "rocm-master", rocmMailRecipients)
//}

// Runs on debug build on master (triggered nightly)
multiJob("caffe2-master-debug") {
  masterJobSettings(delegate, "pytorch/pytorch", false, '-DCMAKE_BUILD_TYPE=DEBUG', "master", mailRecipients)
  triggers {
    cron('@daily')
  }
}

def pullRequestJobSettings = { context, repo, commitSource ->
  context.with {
    JobUtil.gitHubPullRequestTrigger(delegate, repo, pytorchbotAuthId, Users)
    parameters {
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)
      ParametersUtil.CMAKE_ARGS(delegate)
      ParametersUtil.HYPOTHESIS_SEED(delegate)
    }
    wrappers {
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      credentialsBinding {
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    } // wrappers
    steps {
      def gitPropertiesFile = './git.properties'

      GitUtil.mergeStep(delegate)
      GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

      environmentVariables {
        propertiesFile(gitPropertiesFile)
      }

      phase("Build and test") {
        // PyTorch
        buildEnvironments.each {
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

        // Caffe2
        def definePhaseJob = { name, caffe2_only ->
          phaseJob("caffe2-builds/${name}") {
            parameters {
              // Pass parameters of this job
              currentBuild()
              // override DOCKER_IMAGE_TAG
              predefinedProp('DOCKER_IMAGE_TAG', '${CAFFE2_DOCKER_IMAGE_TAG}')
              predefinedProp('GITHUB_REPO', repo)
              // See https://github.com/jenkinsci/ghprb-plugin/issues/591
              predefinedProp('ghprbCredentialsId', pytorchbotAuthId)
              // Ensure consistent merge behavior in downstream builds.
              propertiesFile(gitPropertiesFile)
            }
          }
        }

        Caffe2Images.buildAndTestEnvironments.each {
          def caffe2_only = !Caffe2Images.integratedEnvironments.contains(it);
          definePhaseJob(it + "-trigger-test", caffe2_only)
        }

        Caffe2Images.buildOnlyEnvironments.each {
          def caffe2_only = !Caffe2Images.integratedEnvironments.contains(it);
          definePhaseJob(it + "-trigger-build", caffe2_only)
        }
      }
    }
  }
}

multiJob("pytorch-pull-request") {
  pullRequestJobSettings(delegate, "pytorch/pytorch", "pull-request")
}

multiJob("rocm-pytorch-pull-request") {
  pullRequestJobSettings(delegate, "ROCmSoftwarePlatform/pytorch", "rocm-pull-request")
}

def lintCheckBuildEnvironment = 'pytorch-linux-trusty-py2.7'

// One job per build environment
(buildEnvironments + experimentalBuildEnvironments).each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  def numParallelTests = 1;
  if (splitTestEnvironments.any { it.contains(buildEnvironment) }) {
    numParallelTests = 2
  }

  def testConfigs = [""]  // "" is the default config
  if (buildEnvironment == avxConfigTestEnvironment) {
    testConfigs.add("-NO_AVX2")
    testConfigs.add("-NO_AVX-NO_AVX2")
  }

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
      ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)
      ParametersUtil.COMMIT_SOURCE(delegate)
      ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')

      booleanParam(
        'RUN_TESTS',
        true,
        'Whether to run tests or not',
      )

      booleanParam(
        'DOC_PUSH',
        false,
        'Whether to push doc or not',
      )
    }

    wrappers {
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      credentialsBinding {
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    } // wrappers

    steps {
      def builtImageTag = 'tmp-${DOCKER_IMAGE_TAG}-${GIT_COMMIT}'
      def caffe2BuiltImageTag = 'tmp-${CAFFE2_DOCKER_IMAGE_TAG}-${GIT_COMMIT}'
      def builtImageId = '${GIT_COMMIT}'

      if (buildEnvironment.contains("docker")) {
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
      } else if (buildEnvironment.contains("macos") && buildEnvironment.contains("cuda")) {
        // Build only for macOS CUDA CI
        phase("Build") {
          phaseJob("${buildBasePath}/${buildEnvironment}-build") {
            parameters {
              currentBuild()
              predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
              predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
              predefinedProp('IMAGE_COMMIT_ID', builtImageId)
              predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
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
              predefinedProp('CAFFE2_DOCKER_IMAGE_TAG', '${CAFFE2_DOCKER_IMAGE_TAG}')
              predefinedProp('DOCKER_IMAGE_COMMIT_TAG', builtImageTag)
              predefinedProp('CAFFE2_DOCKER_IMAGE_COMMIT_TAG', caffe2BuiltImageTag)
              predefinedProp('IMAGE_COMMIT_ID', builtImageId)
              predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
            }
          }
        }
        phase("Test and Push") {
          for (config in testConfigs) {
            for (i = 1; i <= numParallelTests; i++) {
              def suffix = (numParallelTests > 1) ? Integer.toString(i) : ""
              phaseJob("${buildBasePath}/${buildEnvironment}" + config + "-test" + suffix) {
                parameters {
                  currentBuild()
                  predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
                  predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
                  predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
                  predefinedProp('CAFFE2_DOCKER_IMAGE_TAG', caffe2BuiltImageTag)
                  predefinedProp('IMAGE_COMMIT_ID', builtImageId)
                  predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
                }
              }
            }
          }
          if (buildEnvironment == perfTestEnvironment) {
            // NB: We're limited to 10 at the moment
            phaseJob("${buildBasePath}/${buildEnvironment}-multigpu-test") {
              parameters {
                currentBuild()
                predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
                predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
                predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
                predefinedProp('CAFFE2_DOCKER_IMAGE_TAG', caffe2BuiltImageTag)
                predefinedProp('IMAGE_COMMIT_ID', builtImageId)
                predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
              }
            }
          }
          if (buildEnvironment == docAndTutorialEnvironment) {
            phaseJob("${buildBasePath}/doc-push") {
              parameters {
                predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
                predefinedProp('CAFFE2_DOCKER_IMAGE_TAG', caffe2BuiltImageTag)
                predefinedProp('DOC_PUSH', '${DOC_PUSH}')
                predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
              }
              PhaseJobUtil.condition(delegate, '"${COMMIT_SOURCE}" == "master"')
            }
          }
          if (buildEnvironment == perfTestEnvironment) {
            // yf225: CPU perf test is flaky
            // phaseJob("${buildBasePath}/short-perf-test-cpu") {
            //   parameters {
            //     predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
            //     predefinedProp('COMMIT_SOURCE', '${COMMIT_SOURCE}')
            //   }
            // }
            phaseJob("${buildBasePath}/short-perf-test-gpu") {
              parameters {
                predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
                predefinedProp('CAFFE2_DOCKER_IMAGE_TAG', caffe2BuiltImageTag)
                predefinedProp('COMMIT_SOURCE', '${COMMIT_SOURCE}')
                predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
              }
            }
          }
        } // phase("Test and Push")
      } // else (buildEnvironment.contains("macos") || buildEnvironment.contains("docker"))
    } // steps
  } // multiJob("${buildBasePath}/${buildEnvironment}-trigger")

  if (buildEnvironment.contains('linux') || isRocmBuild(buildEnvironment)) {
  job("${buildBasePath}/${buildEnvironment}-build") {
    if (isRocmBuild(buildEnvironment)) {
      // ROCm builds OOM with only 4G of RAM.  We need more.
      JobUtil.common delegate, 'docker && bigcpu'
    } else {
      JobUtil.common delegate, 'docker && cpu'
    }
    JobUtil.timeoutAndFailAfter(delegate, 300)
    JobUtil.gitCommitFromPublicGitHub(delegate, '${GITHUB_REPO}')

    parameters {
      ParametersUtil.GIT_COMMIT(delegate)
      ParametersUtil.GIT_MERGE_TARGET(delegate)

      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)

      ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')

      stringParam(
        'DOCKER_IMAGE_COMMIT_TAG',
        '',
        'Tag of image to commit and push after this build completes (if non-empty)',
      )

      stringParam(
        'CAFFE2_DOCKER_IMAGE_COMMIT_TAG',
        '',
        'Tag of image to commit and push after this build completes (if non-empty) for Caffe2-based images',
      )
    }

    wrappers {
      // This is needed so that Jenkins knows to hide these strings in all the console outputs
      credentialsBinding {
        usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
      }
    } // wrappers

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
              image: dockerImage(buildEnvironment, '${BUILD_ENVIRONMENT}','${DOCKER_IMAGE_TAG}','${CAFFE2_DOCKER_IMAGE_TAG}'),
              commitImage: dockerImage(buildEnvironment, '${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_COMMIT_TAG}', 'pt_${CAFFE2_DOCKER_IMAGE_COMMIT_TAG}'),
              workspaceSource: "host-copy",
              script: '''
set -ex

# Reinitialize submodules
git submodule update --init || git submodule update --init || git submodule update --init

# sccache will fail for CUDA builds if all cores are used for compiling
# TODO: move this into build.sh (https://github.com/pytorch/pytorch/pull/7361)
if [[ "$BUILD_ENVIRONMENT" == *cuda* ]] && which sccache > /dev/null; then
  export MAX_JOBS=`expr $(nproc) - 1`
fi

if test -x ".jenkins/pytorch/build.sh"; then
  .jenkins/pytorch/build.sh
else
  .jenkins/build.sh
fi

# Clean up temporary build products so that the docker image we commit
# has less size
git clean -xfd

exit 0
'''
    }

    publishers {
      groovyPostBuild {
        script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript(mailRecipients))
      }
    }
  }


  if (buildEnvironment == docAndTutorialEnvironment) {
    job("${buildBasePath}/doc-push") {
      JobUtil.common delegate, 'docker && cpu'
      // Explicitly disable concurrent build because this job is racy.
      concurrentBuild(false)
      parameters {
        // TODO: Accept GIT_COMMIT, eliminate race, small profit
        ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
        ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)
        booleanParam(
          'DOC_PUSH',
          false,
          'Whether to doc push or not',
        )
      }
      wrappers {
        // This is needed so that Jenkins knows to hide these strings in all the console outputs
        credentialsBinding {
          usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
        }
      } // wrappers
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
                image: dockerImage(buildEnvironment, '${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_TAG}','${CAFFE2_DOCKER_IMAGE_TAG}'),
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

    job("${buildBasePath}/tutorial-push") {
      JobUtil.common delegate, 'docker && gpu'
      JobUtil.timeoutAndFailAfter(delegate, 3000)
      // Explicitly disable concurrent build because this job is racy.
      concurrentBuild(false)
      parameters {
        ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
        ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)
        booleanParam(
          'DOC_PUSH',
          false,
          'Whether to tutorial push or not',
        )
      }
      wrappers {
        // This is needed so that Jenkins knows to hide these strings in all the console outputs
        credentialsBinding {
          usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
        }
      } // wrappers
      scm {
        git {
          remote {
            github('facebookmicrosites/pytorch-tutorials', 'ssh')
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
                image: dockerImage(buildEnvironment, '${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_TAG}','${CAFFE2_DOCKER_IMAGE_TAG}'),
                workspaceSource: "host-mount",
                script: '''
set -ex

if [ "${DOC_PUSH:-true}" == "false" ]; then
  echo "Skipping tutorial push..."
  exit 0
fi

sudo apt-get update
sudo apt-get install -y --no-install-recommends unzip p7zip-full sox libsox-dev libsox-fmt-all

export PATH=/opt/conda/bin:$PATH
# pillow >= 4.2 will throw error when trying to write mode RGBA as JPEG,
# this is a workaround to the issue.
conda install -y sphinx pandas pillow=4.1.1
pip install sphinx-gallery sphinx_rtd_theme tqdm matplotlib ipython

git clone https://github.com/pytorch/vision --quiet
pushd vision
pip install . --no-deps  # We don't want it to install the stock PyTorch version from pip
popd

git clone https://github.com/pytorch/audio --quiet
pushd audio
python setup.py install
popd

git clean -xdf
git checkout -- .
git checkout gh-pages

git clone https://github.com/facebookmicrosites/pytorch-tutorials tutorials_repo
pushd tutorials_repo

# Download dataset for beginner_source/dcgan_faces_tutorial.py
curl https://s3.amazonaws.com/pytorch-datasets/img_align_celeba.zip --output img_align_celeba.zip
sudo mkdir -p /home/ubuntu/facebook/datasets/celeba
sudo chmod -R 0777 /home/ubuntu/facebook/datasets/celeba
unzip img_align_celeba.zip -d /home/ubuntu/facebook/datasets/celeba > null

# Download dataset for beginner_source/hybrid_frontend/introduction_to_hybrid_frontend_tutorial.py
mkdir data/
curl https://s3.amazonaws.com/pytorch-datasets/iris.data --output data/iris.data

# Download dataset for beginner_source/audio_classifier_tutorial.py
curl https://s3.amazonaws.com/pytorch-datasets/UrbanSound8K.tar.gz --output UrbanSound8K.tar.gz
tar -xzf UrbanSound8K.tar.gz -C ./beginner_source

# We will fix the hybrid frontend tutorials when the API is stable
rm beginner_source/hybrid_frontend/learning_hybrid_frontend_through_example_tutorial.py
rm beginner_source/hybrid_frontend/introduction_to_hybrid_frontend_tutorial.py

make docs
popd

rm -rf vision
rm -rf audio

cp -r tutorials_repo/docs/* ./
rm -rf tutorials_repo

git status
git add -A || true
git config user.email "soumith+bot@pytorch.org"
git config user.name "pytorchbot"
git commit -m "Automated tutorials push" || true
git status
'''
      }
      // WARNING WARNING WARNING: This block is unusual!  Look twice before
      // copy pasting!
      publishers {
        git {
          // TODO: This has the race in the no-op case with DOC_PUSH=false. Oops.
          pushOnlyIfSuccess()
          branch('origin', 'gh-pages')
        }
      }
    }
  }

  if (buildEnvironment == perfTestEnvironment) {
    job("${buildBasePath}/short-perf-test-cpu") {
      JobUtil.common delegate, 'cpu-perf-test'

      parameters {
        ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
        ParametersUtil.COMMIT_SOURCE(delegate)
        ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)
      }

      wrappers {
        credentialsBinding {
          usernamePassword('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'iam-user-perf')
          // This is needed so that Jenkins knows to hide these strings in all the console outputs
          usernamePassword('JENKINS_USERNAME', 'JENKINS_PASSWORD', 'JENKINS_USERNAME_AND_PASSWORD')
        }
      }

      steps {
        environmentVariables {
          env(
            'BUILD_ENVIRONMENT',
            "${buildEnvironment}",
          )
          env(
            'CPU_PERF_TEST',
            true,
          )
        }

        DockerUtil.shell context: delegate,
                image: dockerImage(buildEnvironment, '${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_TAG}','${CAFFE2_DOCKER_IMAGE_TAG}'),
                workspaceSource: "docker",
                script: '''
if test -x ".jenkins/pytorch/short-perf-test-cpu.sh"; then
  .jenkins/pytorch/short-perf-test-cpu.sh
else
  .jenkins/short-perf-test-cpu.sh
fi
'''
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript(mailRecipients))
        }
      }
    }

    job("${buildBasePath}/short-perf-test-gpu") {
      JobUtil.common delegate, 'docker && gpu'

      parameters {
        ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
        ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)
        ParametersUtil.COMMIT_SOURCE(delegate)
      }

      wrappers {
        credentialsBinding {
          usernamePassword('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'iam-user-perf')
          // This is needed so that Jenkins knows to hide these strings in all the console outputs
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
                cudaVersion: cudaVersion,
                image: dockerImage(buildEnvironment, '${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_TAG}','${CAFFE2_DOCKER_IMAGE_TAG}'),
                workspaceSource: "docker",
                script: '''
if test -x ".jenkins/pytorch/short-perf-test-gpu.sh"; then
  .jenkins/pytorch/short-perf-test-gpu.sh
else
  .jenkins/short-perf-test-gpu.sh
fi
'''
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript(mailRecipients))
        }
      }
    } // job(... + "short-perf-test-gpu")
  } // if (buildEnvironment == perfTestEnvironment)

  for (config in testConfigs) {
    for (i = 1; i <= numParallelTests; i++) {
      def suffix = (numParallelTests > 1) ? Integer.toString(i) : ""
      job("${buildBasePath}/${buildEnvironment}" + config + "-test" + suffix) {
        if (isRocmBuild(buildEnvironment)) {
          JobUtil.common delegate, 'docker && rocm'
        } else {
          JobUtil.common delegate, buildEnvironment.contains('cuda') ? 'docker && gpu' : 'docker && cpu'
        }
        JobUtil.timeoutAndFailAfter(delegate, 120)

        parameters {
          ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
          ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)

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
          // TODO: Will be obsolete once this is baked into docker image
          environmentVariables {
            env(
              'BUILD_ENVIRONMENT',
              "${buildEnvironment}",
            )
          }

          DockerUtil.shell context: delegate,
                  cudaVersion: cudaVersion,
                  image: dockerImage(buildEnvironment, '${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_TAG}', 'pt_${CAFFE2_DOCKER_IMAGE_TAG}'),
                  workspaceSource: "docker",
                  script: '''
  set -ex

  if [ "${RUN_TESTS:-true}" == "false" ]; then
    echo "Skipping tests..."
    exit 0
  fi

  if test -x ".jenkins/pytorch/test.sh"; then
    .jenkins/pytorch/test.sh
  else
    .jenkins/test.sh
  fi

  exit 0
  '''
        }

        publishers {
          groovyPostBuild {
            script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript(mailRecipients))
          }
        }
      } // job(... + "-test")
    }
  }


  job("${buildBasePath}/${buildEnvironment}-multigpu-test") {
    JobUtil.common delegate, 'docker && multigpu'
    JobUtil.timeoutAndFailAfter(delegate, 30)

    parameters {
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)
    }

    steps {
      // TODO: Will be obsolete once this is baked into docker image
      environmentVariables {
        env(
          'BUILD_ENVIRONMENT',
          "${buildEnvironment}",
        )
      }

      DockerUtil.shell context: delegate,
              cudaVersion: cudaVersion,
              image: dockerImage(buildEnvironment, '${BUILD_ENVIRONMENT}', '${DOCKER_IMAGE_TAG}','${CAFFE2_DOCKER_IMAGE_TAG}'),
              workspaceSource: "docker",
              script: '''
set -ex

if [[ -x .jenkins/pytorch/multigpu-test.sh ]]; then
  .jenkins/pytorch/multigpu-test.sh
else
  .jenkins/multigpu-test.sh
fi

exit 0
'''
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript(mailRecipients))
        }
      }
    } // job(... + "-multigpu-test")


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
      JobUtil.gitCommitFromPublicGitHub delegate, '${GITHUB_REPO}'

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)
        ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')
      }

      steps {
        GitUtil.mergeStep(delegate)

        shell '''#!/bin/bash
set -ex

if test -x ".jenkins/pytorch/docker-build-test.sh"; then
  .jenkins/pytorch/docker-build-test.sh
else
  .jenkins/docker-build-test.sh
fi

# PURPOSEFULLY DO NOT PUSH THIS IMAGE.  We are not sure if
# the registry will GC them quickly enough.

# TODO: run some simple CPU tests inside the image
'''
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript(mailRecipients))
        }
      }
    }
  } // if (buildEnvironment.contains("docker"))

  if (buildEnvironment.contains("macos")) {
    job("${buildBasePath}/${buildEnvironment}-build") {
      JobUtil.common delegate, 'osx'
      JobUtil.gitCommitFromPublicGitHub(delegate, '${GITHUB_REPO}')

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)
        ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')

        stringParam(
          'IMAGE_COMMIT_ID',
          '',
          "Identifier for built torch package"
        )
      }

      steps {
        GitUtil.mergeStep(delegate)

        // Don't delete this envvar because we have Python script that uses it
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

        MacOSUtil.sandboxShell delegate, '''
export PATH=/usr/local/bin:$PATH
chmod +x .jenkins/pytorch/macos-build.sh
if test -x ".jenkins/pytorch/macos-build.sh"; then
  .jenkins/pytorch/macos-build.sh
else
  .jenkins/macos-build.sh
fi
'''
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript(mailRecipients))
        }
      }
    }
    // Don't run tests for macOS CUDA CI because we don't have macOS GPU machine
    if (!buildEnvironment.contains("cuda")) {
      for (i = 1; i <= numParallelTests; i++) {
        def suffix = (numParallelTests > 1) ? Integer.toString(i) : ""
        job("${buildBasePath}/${buildEnvironment}-test" + suffix) {
          JobUtil.common delegate, 'osx'
          JobUtil.gitCommitFromPublicGitHub(delegate, '${GITHUB_REPO}')

          parameters {
            ParametersUtil.GIT_COMMIT(delegate)
            ParametersUtil.GIT_MERGE_TARGET(delegate)
            ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')

            stringParam(
              'IMAGE_COMMIT_ID',
              '',
              "Identifier for built torch package"
            )
          }

          steps {
            GitUtil.mergeStep(delegate)

            // Don't delete this envvar because we have Python script that uses it
            environmentVariables {
              env(
                'BUILD_ENVIRONMENT',
                "${buildEnvironment}",
              )
            }

            MacOSUtil.sandboxShell delegate, '''
  export PATH=/usr/local/bin:$PATH
  chmod +x .jenkins/pytorch/macos-test.sh
  if test -x ".jenkins/pytorch/macos-test.sh"; then
    .jenkins/pytorch/macos-test.sh
  else
    .jenkins/macos-test.sh
  fi
  '''
          }

          publishers {
            groovyPostBuild {
              script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript(mailRecipients))
            }
          }
        }
      }
    }
  } // if (buildEnvironment.contains("macos"))

  if (buildEnvironment.contains('win')) {
    job("${buildBasePath}/${buildEnvironment}-build") {
      JobUtil.common delegate, 'windows && cpu'
      JobUtil.gitCommitFromPublicGitHub delegate, '${GITHUB_REPO}'
      JobUtil.timeoutAndFailAfter(delegate, 40)
      // Windows builds are something like 9M a pop, so keep less of
      // them.
      publishers {
        publishBuild {
          discardOldBuilds(30)
        }
      }

      parameters {
        ParametersUtil.GIT_COMMIT(delegate)
        ParametersUtil.GIT_MERGE_TARGET(delegate)
        ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')

        stringParam(
          'IMAGE_COMMIT_ID',
          '',
          "Identifier for built torch package"
        )
      }

      steps {
        GitUtil.mergeStep(delegate)

        // Don't delete this envvar because we have Python script that uses it
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

        WindowsUtil.shell delegate, '''
if test -x ".jenkins/pytorch/win-build.sh"; then
  .jenkins/pytorch/win-build.sh
else
  .jenkins/win-build.sh
fi
''', cudaVersion
      }

      publishers {
        groovyPostBuild {
          script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript(mailRecipients))
        }
      }
    }

    // Windows
    for (i = 1; i <= numParallelTests; i++) {
      def suffix = (numParallelTests > 1) ? Integer.toString(i) : ""
      job("${buildBasePath}/${buildEnvironment}-test" + suffix) {
        JobUtil.common delegate, 'windows && gpu'
        JobUtil.gitCommitFromPublicGitHub(delegate, '${GITHUB_REPO}')
        JobUtil.timeoutAndFailAfter(delegate, 40)

        parameters {
          ParametersUtil.GIT_COMMIT(delegate)
          ParametersUtil.GIT_MERGE_TARGET(delegate)
          ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')

          stringParam(
            'IMAGE_COMMIT_ID',
            '',
            "Identifier for built torch package"
          )
        }

        steps {
          GitUtil.mergeStep(delegate)

          // Don't delete this envvar because we have Python script that uses it
          environmentVariables {
            env(
              'BUILD_ENVIRONMENT',
              "${buildEnvironment}",
            )
          }

          WindowsUtil.shell delegate, '''
  if test -x ".jenkins/pytorch/win-test.sh"; then
    .jenkins/pytorch/win-test.sh
  else
    .jenkins/win-test.sh
  fi
  ''', cudaVersion
        }

        publishers {
          groovyPostBuild {
            script(EmailUtil.sendEmailScript + EmailUtil.ciFailureEmailScript(mailRecipients))
          }
        }
      } // job("${buildBasePath}/${buildEnvironment}-test")
    }
  } // if (buildEnvironment.contains("win"))
} // buildEnvironments.each

multiJob("pytorch-tutorial-push") {
  delegate.with {
    throttleConcurrentBuilds {
      maxTotal(1)
    }
    parameters {
      ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
      ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)
    }
    steps {
      def gitPropertiesFile = './git.properties'
      GitUtil.resolveAndSaveParameters(delegate, gitPropertiesFile)

      phase("Master jobs") {
        phaseJob("${buildBasePath}/pytorch-tutorial-push-trigger") {
          parameters {
            // Pass parameters of this job
            currentBuild()
            // Checkout this exact same revision in downstream builds.
            gitRevision()
            propertiesFile(gitPropertiesFile)
            predefinedProp('COMMIT_SOURCE', "master")
            predefinedProp('GITHUB_REPO', "pytorch/pytorch")
          }
        }
      }
    }
    publishers {
      mailer(mailRecipients, false, true)
      downstream('pytorch-tutorial-push', 'FAILURE')
    }
  }
}

multiJob("${buildBasePath}/pytorch-tutorial-push-trigger") {
  JobUtil.commonTrigger(delegate)

  parameters {
    ParametersUtil.GIT_COMMIT(delegate)
    ParametersUtil.GIT_MERGE_TARGET(delegate)
    ParametersUtil.DOCKER_IMAGE_TAG(delegate, DockerVersion.version)
    ParametersUtil.CAFFE2_DOCKER_IMAGE_TAG(delegate, Caffe2DockerVersion.version)
    ParametersUtil.COMMIT_SOURCE(delegate)
    ParametersUtil.GITHUB_REPO(delegate, 'pytorch/pytorch')
  }

  steps {
    def builtImageTag = 'tmp-${DOCKER_IMAGE_TAG}-${GIT_COMMIT}'
    def caffe2BuiltImageTag = 'tmp-${CAFFE2_DOCKER_IMAGE_TAG}-${GIT_COMMIT}'
    def builtImageId = '${GIT_COMMIT}'

    phase("Build") {
      phaseJob("${buildBasePath}/${docAndTutorialEnvironment}-build") {
        parameters {
          currentBuild()
          predefinedProp('GIT_COMMIT', '${GIT_COMMIT}')
          predefinedProp('GIT_MERGE_TARGET', '${GIT_MERGE_TARGET}')
          predefinedProp('DOCKER_IMAGE_TAG', '${DOCKER_IMAGE_TAG}')
          predefinedProp('CAFFE2_DOCKER_IMAGE_TAG', '${CAFFE2_DOCKER_IMAGE_TAG}')
          predefinedProp('DOCKER_IMAGE_COMMIT_TAG', builtImageTag)
          predefinedProp('CAFFE2_DOCKER_IMAGE_COMMIT_TAG', caffe2BuiltImageTag)
          predefinedProp('IMAGE_COMMIT_ID', builtImageId)
          predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
        }
      }
    }
    phase("Test and Push") {
      phaseJob("${buildBasePath}/tutorial-push") {
        parameters {
          predefinedProp('DOCKER_IMAGE_TAG', builtImageTag)
          predefinedProp('CAFFE2_DOCKER_IMAGE_TAG', caffe2BuiltImageTag)
          predefinedProp('DOC_PUSH', '${DOC_PUSH}')
          predefinedProp('GITHUB_REPO', '${GITHUB_REPO}')
        }
        PhaseJobUtil.condition(delegate, '"${COMMIT_SOURCE}" == "master"')
      }
    } // phase("Test and Push")
  } // steps
} // multiJob("${buildBasePath}/pytorch-tutorial-push-trigger")
