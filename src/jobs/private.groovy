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
  curl -O https://raw.githubusercontent.com/pytorch/pytorch/master/.circleci/config.yml
  while read line; do
    if [[ "$line" == *Caffe2DockerVersion* ]]; then
      export caffe2DockerImageTag=${line:22}  # len("# Caffe2DockerVersion:") == 22
      echo "caffe2DockerImageTag: "${caffe2DockerImageTag}
      break
    fi
  done < config.yml
  docker run -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY ecr-gc \
    --filter-prefix ${PROJECT} \
    --ignore-tags ${caffe2DockerImageTag}

elif [[ ${PROJECT} == *pytorch* ]]; then
  curl -O https://raw.githubusercontent.com/pytorch/pytorch/master/.circleci/config.yml
  while read line; do
    if [[ "$line" == *PyTorchDockerVersion* ]]; then
      export pyTorchDockerImageTag=${line:23}  # len("# PyTorchDockerVersion:") == 23
      echo "PyTorchDockerImageTag: "${pyTorchDockerImageTag}
      break
    fi
  done < config.yml
  docker run -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY ecr-gc \
    --filter-prefix ${PROJECT} \
    --ignore-tags ${pyTorchDockerImageTag}

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
