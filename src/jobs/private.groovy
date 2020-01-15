import static ossci.caffe2.DockerVersion.allDeployedVersions as caffe2DockerImageTags
import static ossci.pytorch.DockerVersion.allDeployedVersions as pyTorchDockerImageTags
import static ossci.tensorcomp.DockerVersion.version as tensorcompDockerImageTag
import static ossci.translate.DockerVersion.version as translateDockerImageTag
import ossci.DockerUtil
import ossci.EmailUtil

def buildBasePath = 'private'

def mailRecipients = "ezyang@fb.com pietern@fb.com willfeng@fb.com englund@fb.com suo@fb.com kostmo@fb.com zrphercule@fb.com mingbo@fb.com kimishpatel@fb.com"

folder(buildBasePath) {
  description 'Jobs related to running this Jenkins setup'
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
