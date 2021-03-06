import javaposse.jobdsl.dsl.helpers.step.MultiJobStepContext
import ossci.horizon.Images

def dockerBasePath = 'horizon-docker'

// Put all the Docker image building related jobs in a single folder
folder(dockerBasePath) {
  description 'Jobs concerning building Docker images for facebookresearch/Horizon builds'
}

multiJob("horizon-docker-trigger") {
  label('simple')

  parameters {
    stringParam(
      'GIT_COMMIT',
      'origin/master',
      'Refspec of commit to use (e.g. origin/master)',
    )
  }

  steps {
    phase("Build images") {
      Images.dockerImages.each {
        phaseJob("horizon-docker/${it}") {
          parameters {
            currentBuild()
            predefinedProp('UPSTREAM_BUILD_ID', '${BUILD_ID}')
          }
        }
      }
    }
    phase("Test master with new images") {
      phaseJob("horizon-master") {
        parameters {
          predefinedProp('DOCKER_IMAGE_TAG', '${BUILD_ID}')
        }
      }
    }
    phase("Deploy new images") {
      phaseJob("${dockerBasePath}/deploy") {
        parameters {
          predefinedProp('DOCKER_IMAGE_TAG', '${BUILD_ID}')
        }
      }
    }
  }
}

Images.dockerImages.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  job("${dockerBasePath}/${buildEnvironment}") {
    label('docker && cpu')

    parameters {
      stringParam(
        'GIT_COMMIT',
        'origin/master',
        'Refspec of commit to use (e.g. origin/master)',
      )
      stringParam(
        'UPSTREAM_BUILD_ID',
        '',
        '',
      )
    }

    throttleConcurrentBuilds {
      categories(["docker-build"])
      maxTotal(4)
    }

    scm {
      git {
        remote {
          github('facebookresearch/Horizon')
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

    wrappers {
      timestamps()
    }

    steps {
      // Note: uses UPSTREAM_BUILD_ID to make sure all images
      // are tagged with the same number, even if the build numbers
      // of the individual builds are different.
      shell '''#!/bin/bash

set -ex

retry () {
    $*  || (sleep 1 && $*) || (sleep 2 && $*)
}

# If UPSTREAM_BUILD_ID is set (see trigger job), then we can
# use it to tag this build with the same ID used to tag all other
# base image builds. Also, we can try and pull the previous
# image first, to avoid rebuilding layers that haven't changed.
if [ -z "${UPSTREAM_BUILD_ID}" ]; then
  tag="adhoc-${BUILD_ID}"
else
  last_tag="$((UPSTREAM_BUILD_ID - 1))"
  tag="${UPSTREAM_BUILD_ID}"
fi

registry="308535385114.dkr.ecr.us-east-1.amazonaws.com"
image="${registry}/horizon/${JOB_BASE_NAME}"

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
cd ./docker/jenkins
./build.sh ${JOB_BASE_NAME} -t "${image}:${tag}"
docker push "${image}:${tag}"
'''
    }
  }
}

job("${dockerBasePath}/deploy") {
  parameters {
    stringParam(
      'DOCKER_IMAGE_TAG',
      "",
      'Tag of Docker image to deploy',
    )
  }
  wrappers {
    timestamps()
  }
  label('simple')
  scm {
    git {
      remote {
        github('pytorch/ossci-job-dsl', 'ssh')
        credentials('pytorchbot')
      }
      branch('origin/master')
    }
  }
  steps {
    shell '''#!/bin/bash

set -ex

if [ -z "${DOCKER_IMAGE_TAG}" ]; then
  echo "DOCKER_IMAGE_TAG not set; I don't know what docker image to deploy"
  exit 1
fi

cat > src/main/groovy/ossci/horizon/DockerVersion.groovy <<EOL
// This file is automatically generated
package ossci.horizon
class DockerVersion {
  static final String version = "${DOCKER_IMAGE_TAG}";
}
EOL
git add src/main/groovy/ossci/horizon/DockerVersion.groovy

git commit -m "Update horizon DockerVersion"
'''
  }
  publishers {
    git {
      pushOnlyIfSuccess()
      branch('origin', 'master')
    }
  }
}
