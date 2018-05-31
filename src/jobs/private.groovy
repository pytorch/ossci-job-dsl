import static ossci.caffe2.DockerVersion.version as caffe2DockerImageTag
import static ossci.pytorch.DockerVersion.version as pyTorchDockerImageTag
import static ossci.tensorcomp.DockerVersion.version as tensorcompDockerImageTag
import ossci.DockerUtil

def buildBasePath = 'private'

folder(buildBasePath) {
  description 'Jobs related to running this Jenkins setup'
}

def ignoreTags = [
  'caffe2': caffe2DockerImageTag,
  'pytorch': pyTorchDockerImageTag,
  'tensorcomp': tensorcompDockerImageTag,
]

['caffe2', 'pytorch', 'tensorcomp'].each {
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
          github('pietern/ossci-job-dsl', 'ssh')
          credentials('caffe2bot')
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
      shell '''
docker build -t ecr-gc resources/ecr-gc
'''

      shell """
docker run -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY ecr-gc \
    --filter-prefix ${project} \
    --ignore-tags ${ignoreTags[project]}
"""
    }
  }
}

multiJob("${buildBasePath}/fix-authorized-keys-trigger") {
  label('trigger')
  steps {
    phase('Fix') {
      dockerWorkers.each {
        def worker = it
        phaseJob("${buildBasePath}/fix-authorized-keys") {
          parameters {
            nodeLabel('WORKER', worker)
          }
        }
      }
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

job("${buildBasePath}/fix-authorized-keys") {
  label('docker')

  parameters {
    nodeParam("WORKER")
  }

  steps {
    shell '''
#!/bin/bash
set -ex

# Any docker image will do (this will get stale)
export DOCKER_IMAGE="ci.pytorch.org/pytorch/pytorch-linux-xenial:48"

docker_args=""

# Needs pseudo-TTY for /bin/cat to hang around
docker_args+="-t"

# Detach so we can use docker exec to run stuff
docker_args+=" -d"

# Mount /home/ubuntu which we're going to overwrite
docker_args+=" -v /home/ubuntu:/var/lib/ubuntu -u $(id -u root)"

docker_args+=" ${DOCKER_IMAGE}"

# We start a container and detach it such that we can run
# a series of commands without nuking the container
echo "Starting container for image ${DOCKER_IMAGE}"
id=$(docker run ${docker_args} /bin/cat)
trap "echo 'Stopping container...' && docker rm -f $id > /dev/null" EXIT

(
    # Use everything below the '####' as script to run
    sed -n '/^####/ { s///; :a; n; p; ba; }' "${BASH_SOURCE[0]}"
) | docker exec -u "$(id -u root)" -i "$id" bash

exit 0

#### SCRIPT TO RUN IN DOCKER CONTAINER BELOW THIS LINE
set -ex
cat /var/lib/ubuntu/.ssh/authorized_keys
echo "OLD AUTHORIZED KEYS"
echo "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQD2OzfH1MIc8OsV9mNDb3ArlEjt8KJmP8XqlCZs0qsG+8EXQulFzxAQw0FnWGHDc3IQ3vZOwSiCH5KqukLamcfO/s0ku/gIsD3DuD6oF7wOAO9HGoPCVeY7MAlwc4uV0FUXjRvPAMc0r5R+6GxYTuPGQmp1drAAbK3Vpho3/9NZW3AlZUbvIQapyYdcQSFpc/aR3BruVS2eLMx3ijhDbuhzRDDTV0wAHEqFrXVGmCij/Qe3aAQpRphtXYrWskYzkqzVOV5cjj0fR02U9NHPTcAmUzk+8hLQgWBH6jkwJdueaUCycEUC9sQS+dbwlH5EDkxUij0q8A+JHanl/IQwOw+z jenkins@aws" >> /var/lib/ubuntu/.ssh/authorized_keys
echo "NEW AUTHORIZED KEYS"
cat /var/lib/ubuntu/.ssh/authorized_keys
'''
  }
}
