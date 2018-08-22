import ossci.caffe2.Images
import ossci.ParametersUtil

def packageDockerBasePath = 'packages-docker'

// Put all the Docker image building related jobs in a single folder
folder(packageDockerBasePath) {
  description 'Jobs concerning building Docker images for binary package builds'
}

multiJob("packages-docker-trigger") {
  label('simple')

  steps {
    phase("Build images") {
      Images.dockerBaseImages.each {
        phaseJob("${packageDockerBasePath}/${it}") {
          parameters {
            currentBuild()
          }
        }
      }
    }
  } // steps
} // packages-docker-trigger


Images.packagesDockerBaseImages.each {
  def buildEnvironment = it

  job("${packageDockerBasePath}/${buildEnvironment}") {
    label('docker && cpu')

    throttleConcurrentBuilds {
      categories(["docker-build"])
      maxTotal(4)
    }

    wrappers {
      timestamps()
    }

    steps {
      shell '''#!/bin/bash

set -ex

retry () {
    $*  || (sleep 1 && $*) || (sleep 2 && $*)
}

registry="308535385114.dkr.ecr.us-east-1.amazonaws.com"
image="${registry}/packages/${JOB_BASE_NAME}"

login() {
  aws ecr get-authorization-token --region us-east-1 --output text --query 'authorizationData[].authorizationToken' |
    base64 -d |
    cut -d: -f2 |
    docker login -u AWS --password-stdin "$1"
}

# Retry on timeouts (can happen on job stampede).
retry login "${registry}"

# Logout on exit
trap "docker logout ${registry}" EXIT

export EC2=1
export JENKINS=1

# Try to pull the previous image (perhaps we can reuse some layers)
if [ -n "${last_tag}" ]; then
  docker pull "${image}:${last_tag}" || true
fi

# Build new image
docker pull soumith/${JOB_BASE_NAME}
docker tag soumith/${JOB_BASE_NAME} ${image}
docker push "${image}:latest"
'''
    }
  }
}
