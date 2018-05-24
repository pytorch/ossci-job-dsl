package ossci

class MacOSUtil {
  static void shell(
      context,
      String script) {
    String prefix = '''
export PATH=/usr/local/bin:$PATH

# SCRIPT TO RUN IN MACOS BELOW THIS LINE
'''

    context.with {
      shell(prefix + script)
    }
  }

  static void sandboxShell(
      context,
      String script) {
    String prefix = '''
mkdir -p ci_scripts

cat >ci_scripts/sandbox_rules.sb << EOL

(version 1)
(allow default)

; Only allow writing to current directory, ccache, and a few log/temp/output paths
(deny file-write*
    (require-all
        (require-not (subpath "$PWD"))
        (require-not (subpath "$HOME/.ccache"))
        (require-not (subpath "$HOME/pytorch-ci-env"))
        (require-not (subpath "$TMPDIR"))
        (require-not (subpath "/private/tmp"))
        (require-not (subpath "/private/var"))
        (require-not (subpath "/var/tmp"))
        (require-not (literal "/dev/null"))
        (require-not (literal "/dev/zero"))
    )
)

EOL

cat >ci_scripts/run_script.sh << EOL
#!/bin/bash

# SCRIPT TO RUN IN MACOS BELOW THIS LINE
'''

    String suffix = '''
# SCRIPT TO RUN IN MACOS ABOVE THIS LINE

EOL

chmod +x ci_scripts/run_script.sh

sandbox-exec -f ci_scripts/sandbox_rules.sb "$PWD/ci_scripts/run_script.sh"
'''

    context.with {
      shell(prefix + script + suffix)
    }
  }

  static void dockerShell(Map attrs) {
    String prefix = '''#!/bin/bash
#
# Helper to run snippet inside a macOS Docker container.
#

set -ex

export PATH=/usr/local/bin:$PATH

# Clean up old Docker containers, in case any of them are still running due to unclean exit
(docker rm -f $(docker ps -aq) > /dev/null) || true

retry () {
    $*  || (sleep 1 && $*) || (sleep 2 && $*) || (sleep 4 && $*) || (sleep 8 && $*)
}

output=/dev/stdout
# Uncomment this to be
# Non-verbose by default...
# output=/dev/null
# if [ -n "${DEBUG:-}" ]; then
#   set -x
#   output=/dev/stdout
# fi

if [ -z "${DOCKER_IMAGE:-}" ]; then
  echo "Please set the DOCKER_IMAGE environment variable..."
  exit 1
fi

export -p | sed -e '/ DOCKER_IMAGE=/d' -e '/ PWD=/d' -e '/ PATH=/d' > ./env
cat ./env

docker_args=""

# Needs pseudo-TTY for /bin/cat to hang around
docker_args+="-t"

# Detach so we can use docker exec to run stuff
docker_args+=" -d"

# Mount the workspace to another location so we can copy files to it
# For host workspace in /var, we need to prefix it with /private in order to be
# bind mounted into Docker containers
# See https://stackoverflow.com/a/45123074 for detail
docker_args+=" -v /private$WORKSPACE:/var/lib/jenkins/host-workspace"

# Working directory is current directory
docker_args+=" -w $(pwd)"

# Image
docker_args+=" ${DOCKER_IMAGE}"

# Sometimes, docker pull will fail with "TLS handshake timed out"
# or "unexpected EOF".  This usually indicates intermittent failure.
# Try again!
retry docker pull "${DOCKER_IMAGE}"

# We start a container and detach it such that we can run
# a series of commands without nuking the container
echo "Starting container for image ${DOCKER_IMAGE}"
id=$(docker run ${docker_args} /bin/cat)
trap "echo 'Stopping container...' && docker rm -f $id > /dev/null" EXIT

# Copy in the env file
docker cp $WORKSPACE/env "$id:/var/lib/jenkins/workspace/env"

# I found the only way to make the command below return the proper
# exit code is by splitting run and exec. Executing run directly
# doesn't propagate a non-zero exit code properly.
(
    # Get into working dir, now that it exists
    echo 'cd /var/lib/jenkins/workspace'

    # Source environment
    echo 'source ./env'

    # Override WORKSPACE environment variable. Every container build
    # uses /var/lib/jenkins/workspace for their $WORKSPACE and $PWD
    # instead of /var/lib/jenkins/workspace/some/build/name.
    # This should improve the ccache hit rate.
    echo 'declare -x WORKSPACE=$PWD'

    # Use everything below the '####' as script to run
    sed -n -e '/^####/ {' -e 's///' -e ':a' -e 'n' -e 'p' -e 'ba' -e '}' "${BASH_SOURCE[0]}"
) | docker exec -u jenkins -i "$id" bash

exit 0

#### SCRIPT TO RUN IN DOCKER CONTAINER BELOW THIS LINE
'''

    attrs.context.with {
      environmentVariables {
        env('DOCKER_IMAGE', attrs.image)
        env('COMMIT_DOCKER_IMAGE', attrs.getOrDefault("commitImage", ""))
      }

      // Optionally login with registry before doing anything
      def credentials = attrs.get("registryCredentials")
      if (credentials != null) {
        def username = credentials[0]
        def password = credentials[1]
        def registry = attrs.image.split('/').first()

        shell """#!/bin/bash
echo "Logging into ${registry}..."
echo ${password} | docker login -u ${username} --password-stdin ${registry}
"""
      }

      shell(prefix + attrs.script)
    }
  }
}
