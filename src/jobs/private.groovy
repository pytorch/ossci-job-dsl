import static ossci.caffe2.DockerVersion.allDeployedVersions as caffe2DockerImageTags
import static ossci.pytorch.DockerVersion.allDeployedVersions as pyTorchDockerImageTags
import static ossci.tensorcomp.DockerVersion.version as tensorcompDockerImageTag
import static ossci.translate.DockerVersion.version as translateDockerImageTag
import ossci.DockerUtil

def buildBasePath = 'private'

folder(buildBasePath) {
  description 'Jobs related to running this Jenkins setup'
}

['caffe2', 'pytorch', 'tensorcomp', 'translate'].each {
  def project = it

  job("${buildBasePath}/docker-registry-cleanup-${project}") {
    triggers {
      cron('@hourly')
    }

    label('trigger')

    logRotator(14)

    concurrentBuild()

    scm {
      git {
        remote {
          github('pytorch/ossci-job-dsl', 'ssh')
          credentials('pytorchbot')
        }
        branch('origin/master')
      }
    }
    wrappers {
      credentialsBinding {
        usernamePassword('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'ecr-gc')
      }
    }
    steps {
      environmentVariables {
        env(
          'PROJECT',
          "${project}",
        )
        env(
          'CAFFE2_DOCKER_IMAGE_TAGS',
          "${caffe2DockerImageTags}",
        )
        env(
          'PYTORCH_DOCKER_IMAGE_TAGS',
          "${pyTorchDockerImageTags}",
        )
        env(
          'TENSORCOMP_DOCKER_IMAGE_TAG',
          "${tensorcompDockerImageTag}",
        )
        env(
          'TRANSLATE_DOCKER_IMAGE_TAG',
          "${translateDockerImageTag}",
        )
      }

      shell '''
docker build -t ecr-gc resources/ecr-gc
'''

      shell '''#!/bin/bash
echo ${PROJECT}
if [[ ${PROJECT} == *caffe2* ]]; then
  docker run -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY ecr-gc \
    --filter-prefix ${PROJECT} \
    --ignore-tags ${CAFFE2_DOCKER_IMAGE_TAGS}

elif [[ ${PROJECT} == *pytorch* ]]; then
  docker run -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY ecr-gc \
    --filter-prefix ${PROJECT} \
    --ignore-tags ${PYTORCH_DOCKER_IMAGE_TAGS}

elif [[ ${PROJECT} == *translate* ]]; then
  docker run -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY ecr-gc \
      --filter-prefix ${PROJECT} \
      --ignore-tags ${TRANSLATE_DOCKER_IMAGE_TAG}

elif [[ ${PROJECT} == *tensorcomp* ]]; then
  docker run -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY ecr-gc \
      --filter-prefix ${PROJECT} \
      --ignore-tags ${TENSORCOMP_DOCKER_IMAGE_TAG}
fi
'''
    }
    publishers {
      mailer('ezyang@fb.com', false, false)
    }
  }
}

job("${buildBasePath}/test-pietern") {
  label('docker')

  wrappers {
    timestamps()
  }

  steps {
    shell '''

mkdir -p ./cores

# Ensure cores are written to /tmp/cores

'''

    // TODO mount <workspace>/cores into /tmp/cores
    DockerUtil.shell context: delegate,
                     image: 'ubuntu:16.04',
                     workspaceSource: "host-mount",
                     script: '''
set -ex

sleep 10 &
kill -ABRT $!
wait
'''
  }
}
