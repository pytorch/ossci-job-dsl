import ossci.caffe2.DockerImages

def dockerBasePath = 'caffe2-docker'

// Put all the Docker image building related jobs in a single folder
folder(dockerBasePath) {
  description 'Jobs concerning building Docker images for Caffe2 builds'
}

multiJob("caffe2-docker-trigger") {
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
      DockerImages.images.each {
        phaseJob("caffe2-docker/${it}") {
          parameters {
            currentBuild()
            predefinedProp('UPSTREAM_BUILD_ID', '${BUILD_ID}')
          }
        }
      }
    }
    phase("Test master with new images") {
      phaseJob("caffe2-master") {
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

multiJob("caffe2-docker-push-dockerhub") {
  label('simple')

  description('''
<p>
Push base images with specified tag (see parameters) to DockerHub at <b>caffe2/caffe2-base</b>.
</p>
''')

  parameters {
    stringParam(
      'DOCKER_IMAGE_TAG',
      '',
      'Tag of Docker image to push to DockerHub (build number of caffe2-docker-trigger job)',
    )
  }

  steps {
    phase('Push images') {
      DockerImages.images.each {
        phaseJob("caffe2-docker/${it}-push") {
          parameters {
            currentBuild()
          }
        }
      }
    }
  }
}

DockerImages.images.each {
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
          github('pytorch/pytorch')
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
image="${registry}/caffe2/${JOB_BASE_NAME}"

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
if [ -d ./docker/caffe2/jenkins ]; then
  cd ./docker/caffe2/jenkins
else
  cd ./docker/jenkins
fi
mkdir -p bin
./build.sh ${JOB_BASE_NAME} -t "${image}:${tag}"
docker push "${image}:${tag}"
'''
    }
  }

  job("${dockerBasePath}/${buildEnvironment}-push") {
    label('docker && cpu')

    parameters {
      stringParam(
        'DOCKER_IMAGE_TAG',
        '',
        'Tag of Docker image to push to DockerHub (build number of caffe2-docker-trigger job)',
      )
    }

    throttleConcurrentBuilds {
      categories(["docker-push"])
      maxTotal(4)
    }

    wrappers {
      timestamps()

      credentialsBinding {
        usernamePassword('USERNAME', 'PASSWORD', 'dockerhub-caffe2bot')
      }
    }

    steps {
      // Inject BUILD_ENVIRONMENT because we'd have to do some string
      // manipulation on JOB_BASE_NAME to extract the build environment.
      environmentVariables {
        env(
          'BUILD_ENVIRONMENT',
          "${buildEnvironment}",
        )
      }

      shell '''#!/bin/bash
set -x

registry="308535385114.dkr.ecr.us-east-1.amazonaws.com"

# Login to DockerHub (the credentials plugin masks these values)
echo "${PASSWORD}" | docker login -u "${USERNAME}" --password-stdin

# Login to our own registry
aws ecr get-authorization-token --region us-east-1 --output text --query 'authorizationData[].authorizationToken' |
  base64 -d |
  cut -d: -f2 |
  docker login -u AWS --password-stdin "$registry"

# Logout on exit
trap 'docker logout' EXIT

# Pull, tag, and push the specified image
local_image="${registry}/caffe2/${BUILD_ENVIRONMENT}:${DOCKER_IMAGE_TAG}"
remote_image="caffe2/caffe2-base:${BUILD_ENVIRONMENT}"
docker pull "${local_image}"
docker tag "${local_image}" "${remote_image}"
docker push "${remote_image}"
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
        github('pietern/ossci-job-dsl', 'ssh')
        credentials('caffe2bot')
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

cat > src/main/groovy/ossci/caffe2/DockerVersion.groovy <<EOL
// This file is automatically generated
package ossci.caffe2
class DockerVersion {
  static final String version = "${DOCKER_IMAGE_TAG}";
}
EOL
git add src/main/groovy/ossci/caffe2/DockerVersion.groovy

git commit -m "Update Caffe2 DockerVersion"
'''
  }
  publishers {
    git {
      pushOnlyIfSuccess()
      branch('origin', 'master')
    }
  }
}
