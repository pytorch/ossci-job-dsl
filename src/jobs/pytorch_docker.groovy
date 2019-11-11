import javaposse.jobdsl.dsl.helpers.step.MultiJobStepContext
import ossci.pytorch.Users

def dockerImages = [
  "pytorch-linux-bionic-clang9-thrift-llvmdev",
  "pytorch-linux-xenial-py2.7.9",
  "pytorch-linux-xenial-py2.7",
  "pytorch-linux-xenial-py3.5",
  "pytorch-linux-xenial-py3.6-gcc5.4",
  "pytorch-linux-xenial-py3.6-gcc4.8",
  "pytorch-linux-xenial-py3.6-gcc7",
  "pytorch-linux-xenial-pynightly",
  "pytorch-linux-xenial-cuda8-cudnn7-py2",
  "pytorch-linux-xenial-cuda8-cudnn7-py3",
  "pytorch-linux-xenial-cuda9-cudnn7-py2",
  "pytorch-linux-xenial-cuda9-cudnn7-py3",
  "pytorch-linux-xenial-cuda9.2-cudnn7-py3-gcc7",
  "pytorch-linux-xenial-cuda10-cudnn7-py3-gcc7", // TODO: To be removed
  "pytorch-linux-xenial-cuda10.1-cudnn7-py3-gcc7",
  "pytorch-linux-xenial-py3-clang5-asan",
  "pytorch-linux-xenial-py3-clang5-android-ndk-r19c",
  "pytorch-linux-xenial-py3.6-clang7",
  // "pytorch-linux-artful-cuda9-cudnn7-py3",

  // yf225: remove this after new GCC 7 docker image is in and PyTorch Trusty GCC 7 job is enabled
  "pytorch-linux-xenial-py3.6-gcc7.2",
]

def dockerBasePath = 'pytorch-docker'

// Put all the Docker image building related jobs in a single folder
folder(dockerBasePath) {
  description 'Jobs concerning building Docker images for PyTorch builds'
}

multiJob("${dockerBasePath}-master") {
  parameters {
    stringParam(
      'sha1',
      'origin/master',
      'Refspec of commit to use (e.g. origin/master)',
    )
  }
  label('simple')
  scm {
    git {
      remote {
        github('pytorch/pytorch-ci-dockerfiles')
        refspec([
            // Fetch all branches
            '+refs/heads/*:refs/remotes/origin/*',
            // Fetch PRs so we can trigger from PRs
            '+refs/pull/*:refs/remotes/origin/pr/*',
          ].join(' '))
      }
      branch('${sha1}')
    }
  }
  triggers {
    // Pushes trigger builds
    githubPush()
    // We also refresh the docker image on a weekly basis
    cron('@weekly')
  }
  steps {
    phase("Build and push images") {
      dockerImages.each {
        phaseJob("${dockerBasePath}/${it}") {
          parameters {
            predefinedProp('UPSTREAM_BUILD_ID', '${BUILD_ID}')
            gitRevision()
          }
        }
      }
    }
  }
  publishers {
    postBuildScripts {
      steps {
      }
      onlyIfBuildFails()
    }
  }
}

// This job cannot trigger
job("${dockerBasePath}-pull-request") {
  label('simple')
  scm {
    git {
      remote {
        github('pytorch/pytorch-ci-dockerfiles')
        refspec('+refs/pull/*:refs/remotes/origin/pr/*')
      }
      branch('${sha1}')
    }
  }
  triggers {
    githubPullRequest {
      admins(Users.githubAdmins)
      userWhitelist(Users.githubUserWhitelist)
      useGitHubHooks()
    }
  }
  steps {
    downstreamParameterized {
      trigger("${dockerBasePath}/trigger") {
        block {
          buildStepFailure('FAILURE')
        }
        parameters {
          gitRevision()
        }
      }
    }
  }
}

dockerImages.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  job("${dockerBasePath}/${buildEnvironment}") {
    parameters {
      stringParam(
        'sha1',
        '',
        'Refspec of commit to use (e.g. origin/master)',
      )
      stringParam(
        'UPSTREAM_BUILD_ID',
        '',
        'Upstream build ID to tag with',
      )
    }

    wrappers {
      timestamps()

      credentialsBinding {
        usernamePassword('USERNAME', 'PASSWORD', 'nexus-jenkins')
      }
    }

    label('docker && cpu')

    scm {
      git {
        remote {
          github('pytorch/pytorch-ci-dockerfiles')
          refspec([
              // Fetch all branches
              '+refs/heads/*:refs/remotes/origin/*',
              // Fetch PRs so we can trigger from PRs
              '+refs/pull/*:refs/remotes/origin/pr/*',
            ].join(' '))
        }
        branch('${sha1}')
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

registry="308535385114.dkr.ecr.us-east-1.amazonaws.com"
image="${registry}/pytorch/${JOB_BASE_NAME}"

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
./build.sh ${JOB_BASE_NAME} -t "${image}:${tag}"

docker push "${image}:${tag}"

docker save -o "${JOB_BASE_NAME}:${tag}.tar" "${image}:${tag}"
aws s3 cp "${JOB_BASE_NAME}:${tag}.tar" "s3://ossci-linux-build/pytorch/base/${JOB_BASE_NAME}:${tag}.tar" --acl public-read
'''
    }
  }
}
