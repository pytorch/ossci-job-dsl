import static ossci.caffe2.DockerVersion.version as caffe2DockerImageTag
import static ossci.pytorch.DockerVersion.version as pyTorchDockerImageTag
import static ossci.tensorcomp.DockerVersion.version as tensorcompDockerImageTag
import ossci.DockerUtil

def buildBasePath = 'private'

folder(buildBasePath) {
  description 'Jobs related to running this Jenkins setup'
}

// def workers = Jenkins.getActiveInstance().getLabel("docker").getNodes();
def dockerWorkers = [
  "worker-c5-xlarge-00",
  //"worker-c5-xlarge-01",
  "worker-c5-xlarge-02",
  "worker-c5-xlarge-03",
  //"worker-c5-xlarge-04",
  "worker-c5-xlarge-05",
  "worker-c5-xlarge-06",
  "worker-c5-xlarge-07",
  // "worker-c5-xlarge-08", // (dead as a doornail; starts but not
  // ssh'able)
  "worker-c5-xlarge-09",
  //"worker-c5-xlarge-10",
  "worker-c5-xlarge-11",
  "worker-c5-xlarge-12",
  "worker-c5-xlarge-13",
  "worker-c5-xlarge-14",
  "worker-c5-xlarge-15",
  // "worker-packet-type-1-00",
  // "worker-packet-type-1-01",
  // "worker-packet-type-1-02",
  // "worker-packet-type-1-03",
  // TC gpu workers
  "worker-g3-4xlarge-01",
  "worker-g3-4xlarge-00"
]

def macOsWorkers = [
  // "worker-macos-high-sierra-0",
  // "worker-macos-high-sierra-1",
  "worker-macos-high-sierra-2",
  "worker-macos-high-sierra-3",
  "worker-macos-high-sierra-4",
  "worker-macos-high-sierra-5",
  "worker-macos-high-sierra-6",
  "worker-macos-high-sierra-7",
  "worker-macos-high-sierra-8",
  "worker-macos-high-sierra-9",
  "worker-macos-high-sierra-10",
]

job("${buildBasePath}/ccachesync") {
  wrappers {
    timestamps()
    credentialsBinding {
      usernamePassword('USERNAME', 'PASSWORD', 'nexus-jenkins')
    }
  }

  label('docker')

  scm {
    github('pietern/inotify-sync')
  }

  steps {
    shell '''
# Login to registry.pytorch.org (the credentials plugin masks these values)
echo "${PASSWORD}" | docker login -u "${USERNAME}" --password-stdin registry.pytorch.org

# Logout on exit
trap 'docker logout registry.pytorch.org' EXIT

# Build binary for Alpine Linux
docker run \
  --rm \
  -v $PWD:/go/src/ccachesync \
  -w /go/src/ccachesync \
  golang:alpine \
  go build -v .

# Inline Dockerfile
cat > Dockerfile <<EOS
FROM alpine:latest
ADD ./ccachesync /usr/bin
RUN mkdir /ccache
VOLUME /ccache
USER 1014:1014
WORKDIR /ccache
EOS

# Build and push final image
docker build -t registry.pytorch.org/ccachesync:latest .
docker push registry.pytorch.org/ccachesync:latest
'''
  }
}

// Builds container for ccache cleanup.
// See ./docker/ccacheclean/README.md for more information.
job("${buildBasePath}/ccacheclean") {
  wrappers {
    timestamps()
    credentialsBinding {
      usernamePassword('USERNAME', 'PASSWORD', 'nexus-jenkins')
    }
  }

  label('docker')

  scm {
    git {
      remote {
        github('pietern/ossci-job-dsl', 'ssh')
        credentials('caffe2bot')
      }
      branch('origin/master')
    }
  }

  triggers {
    cron('@weekly')
  }

  steps {
    shell '''
# Login to registry.pytorch.org (the credentials plugin masks these values)
echo "${PASSWORD}" | docker login -u "${USERNAME}" --password-stdin registry.pytorch.org

# Logout on exit
trap 'docker logout registry.pytorch.org' EXIT

cd ./docker/ccacheclean
docker build -t registry.pytorch.org/ccacheclean:latest .
docker push registry.pytorch.org/ccacheclean:latest
'''
  }
}

multiJob("${buildBasePath}/ccache-cleanup-trigger") {
  triggers {
    cron('@hourly')
  }

  label('simple')

  logRotator(14)

  concurrentBuild()

  steps {
    phase("Cleanup") {
      dockerWorkers.each {
        def worker = it
        phaseJob("${buildBasePath}/ccache-cleanup-docker") {
          parameters {
            nodeLabel("WORKER", "${worker}")
          }
        }
      }
      macOsWorkers.each {
        def worker = it
        phaseJob("${buildBasePath}/ccache-cleanup-macos") {
          parameters {
            nodeLabel("WORKER", "${worker}")
          }
        }
      }
    }
  }
}

job("${buildBasePath}/ccache-cleanup-docker") {
  // Will only be triggered for Docker workers,
  // but include this label as sanity check.
  label('docker')

  logRotator(14)

  parameters {
    nodeParam("WORKER")
  }

  wrappers {
    buildName('cleanup ${WORKER}')
  }

  concurrentBuild()

  steps {
    // Normally ccache cleanup runs automatically, but because we have
    // a separate process adding files to the cache (ccachesync),
    // its counters go out of whack and we need to run cleanup manually.
    shell '''
if [[ "$WORKER" == *macos* ]]; then
  export PATH=/usr/local/bin:$PATH
fi

docker pull registry.pytorch.org/ccacheclean:latest
docker run --rm -v ccache:/ccache registry.pytorch.org/ccacheclean:latest
'''
  }
}

// TODO: In principle, the macos workers have docker, so we should be
// able to use ccache-cleanup-docker for them.  Maybe the ccache path
// is different, however.
job("${buildBasePath}/ccache-cleanup-macos") {
  // Will only be triggered for macOS workers,
  // but include this label as sanity check.
  label('macos')

  logRotator(14)

  parameters {
    nodeParam("WORKER")
  }

  wrappers {
    buildName('cleanup ${WORKER}')
  }

  concurrentBuild()

  steps {
    shell '''
set -e
if [ -d "$HOME/.ccache" ]; then
  du -s $HOME/.ccache
fi

# Add Homebrew to PATH
eval `/usr/libexec/path_helper -s`

ccache -c
'''
  }
}

multiJob("${buildBasePath}/docker-image-cleanup-trigger") {
  triggers {
    cron('@hourly')
  }

  label('simple')

  logRotator(14)

  concurrentBuild()

  steps {
    phase("Cleanup") {
      dockerWorkers.each {
        def worker = it
        phaseJob("${buildBasePath}/docker-image-cleanup") {
          parameters {
            nodeLabel("WORKER", "${worker}")
          }
        }
      }
    }
  }
}

multiJob("${buildBasePath}/workspace-cleanup-trigger") {
  triggers {
    cron('@daily')
  }

  label('simple')

  logRotator(14)

  concurrentBuild()

  steps {
    phase("Cleanup") {
      dockerWorkers.each {
        def worker = it
        phaseJob("${buildBasePath}/workspace-cleanup") {
          parameters {
            nodeLabel("WORKER", "${worker}")
          }
        }
      }
    }
  }
}

job("${buildBasePath}/docker-image-cleanup") {
  logRotator(14)

  parameters {
    nodeParam("WORKER")
  }

  wrappers {
    buildName('cleanup ${WORKER}')
  }
  concurrentBuild()
  steps {
    shell '''
if [[ "$WORKER" == *macos* ]]; then
  export PATH="/usr/local/bin:$PATH"
fi
# Sometimes Docker images hang around even after the job
# is done.  Because we only have a single executor per node,
# it is always safe to kill any Docker stragglers.  So kill
# them all, and this way prune can kill a lot more things.
# NB: This will fail when docker ps -q is empty; we don't care
docker kill $(docker ps -q) || true
docker system prune --filter "until=2h" --all --force
'''
  }
}

job("${buildBasePath}/workspace-cleanup") {
  logRotator(14)

  parameters {
    nodeParam("WORKER")
  }

  wrappers {
    buildName('cleanup ${WORKER}')
  }
  concurrentBuild()
  steps {
    shell '''
rm -rf /data/jenkins/workspace
'''
  }
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

    label('simple')

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
  label('simple')
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
