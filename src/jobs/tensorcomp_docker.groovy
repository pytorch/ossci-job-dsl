import ossci.tensorcomp.DockerImages

def dockerBasePath = 'tensorcomp-docker'

// Put all the Docker image building related jobs in a single folder
folder(dockerBasePath) {
  description 'Jobs concerning building Docker images for tensorcomp builds'
}

multiJob("tensorcomp-docker-trigger") {
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
        phaseJob("tensorcomp-docker/${it}") {
          parameters {
            currentBuild()
            predefinedProp('UPSTREAM_BUILD_ID', '${BUILD_ID}')
          }
        }
      }
    }
    phase("Test master with new images") {
      phaseJob("tensorcomp-master") {
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

multiJob("tensorcomp-docker-push-dockerhub") {
  label('simple')

  description('''
<p>
Push base images with specified tag (see parameters) to DockerHub at <b>tensorcomp/tensorcomp-base</b>.
</p>
''')

  parameters {
    stringParam(
      'DOCKER_IMAGE_TAG',
      '',
      'Tag of Docker image to push to DockerHub (build number of tensorcomp-docker-trigger job)',
    )
  }

  steps {
    phase('Push images') {
      DockerImages.images.each {
        phaseJob("tensorcomp-docker/${it}-push") {
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
          github('facebookresearch/TensorComprehensions')
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

      credentialsBinding {
        usernamePassword('USERNAME', 'PASSWORD', 'nexus-jenkins')
      }
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

image="registry.pytorch.org/tensorcomp/${JOB_BASE_NAME}"

login() {
  echo "${PASSWORD}" | docker login -u "${USERNAME}"  --password-stdin registry.pytorch.org
}

# Login to registry.pytorch.org (the credentials plugin masks these values).
# Retry on timeouts (can happen on job stampede).
retry login

# Logout on exit
trap 'docker logout registry.pytorch.org' EXIT

export EC2=1
export JENKINS=1

# Try to pull the previous image (perhaps we can reuse some layers)
if [ -n "${last_tag}" ]; then
  docker pull "${image}:${last_tag}" || true
fi

# Build new image
cd ./docker/
image=${JOB_BASE_NAME} ./docker_build.sh -t "${image}:${tag}"

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
        'Tag of Docker image to push to DockerHub (build number of tensorcomp-docker-trigger job)',
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

# Login to DockerHub (the credentials plugin masks these values)
echo "${PASSWORD}" | docker login -u "${USERNAME}" --password-stdin

# Logout on exit
trap 'docker logout' EXIT

# Pull, tag, and push the specified image
local_image="registry.pytorch.org/tensorcomp/${BUILD_ENVIRONMENT}:${DOCKER_IMAGE_TAG}"
remote_image="tensorcomp/tensorcomp-base:${BUILD_ENVIRONMENT}"
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

cat > src/main/groovy/ossci/tensorcomp/DockerVersion.groovy <<EOL
// This file is automatically generated
// If you are manually updating this version, please
// also update the tensorcomp one at
// src/main/groovy/ossci/tensorcomp/DockerVersion.groovy
// to the same version
package ossci.tensorcomp
class DockerVersion {
  static final String version = "${DOCKER_IMAGE_TAG}";
}
EOL
git add src/main/groovy/ossci/tensorcomp/DockerVersion.groovy
git commit -m "Update tensorcomp DockerVersion"
'''
  }
  publishers {
    git {
      pushOnlyIfSuccess()
      branch('origin', 'master')
    }
  }
}
