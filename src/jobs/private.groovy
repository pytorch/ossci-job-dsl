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
