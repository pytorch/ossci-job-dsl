package ossci

class DockerUtil {

  // Arguments:
  //  context
  //  image
  //  commitImage
  //  registryCredentials (optional)
  //  cudaVersion
  //  importEnv
  //  workspaceSource
  //    - host-mount: mount the host workspace at the Docker image workspace location
  //    - host-copy: copy the host workspace into the Docker image
  //    - docker: don't do anything, use the Docker workspace directly
  //  script
  static void shell(Map attrs) {
    String prefix = '''#!/bin/bash
#
# Helper to run snippet inside a Docker container.
#
# The Jenkins workspace is mounted at the same path, the working directory
# is the same, the environment is the same, and the user is the same.
#

set -ex

# Turn off GPU autoboost for GPU instances, so that we get consistent performance numbers
if [ -n "${CUDA_VERSION:-}" ]; then
    (sudo /usr/bin/set_gpu_autoboost_off.sh) || true
fi

if [ -n "${CPU_PERF_TEST:-}" ] && [[ $(/bin/hostname) == *packet* ]]; then
  # Clean up old Docker containers, in case any of them are still running due to unclean exit
  (docker rm -f $(docker ps -aq) > /dev/null) || true
fi

retry () {
    $*  || (sleep 1 && $*) || (sleep 2 && $*) || (sleep 4 && $*) || (sleep 8 && $*)
}

# We need to replace "<PREFIX>:tmp-226-origin/master" with "<PREFIX>:tmp-226-origin-master",
# so that docker won't complain when pulling / commiting this image
sanitize_image_tag () {
  UNSANITIZED_IMAGE=$1
  UNSANITIZED_IMAGE_arr=(${UNSANITIZED_IMAGE//:/ })
  IMAGE_PREFIX=${UNSANITIZED_IMAGE_arr[0]}
  IMAGE_TAG_SANITIZED=$(echo ${UNSANITIZED_IMAGE_arr[1]}  | sed -e 's/\\//-/g')
  SANITIZED_IMAGE=${IMAGE_PREFIX}:${IMAGE_TAG_SANITIZED}
}

case "$WORKSPACE_SOURCE" in
  host-mount)
    echo "Mounting host workspace into Docker image"
    ;;
  host-copy)
    echo "Copying host workspace into Docker image"
    ;;
  docker)
    echo "Using Docker image's workspace directly"
    ;;
  *)
    echo "Illegal WORKSPACE_SOURCE; valid values are host-mount, host-copy or docker"
    exit 0
    ;;
esac

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

sanitize_image_tag ${DOCKER_IMAGE}
DOCKER_IMAGE=${SANITIZED_IMAGE}

# TODO: Get rid of this (may need to adjust build scripts)
export -p | sed -e '/ DOCKER_IMAGE=/d' -e '/ PWD=/d' -e '/ PATH=/d' > ./env
cat ./env

docker_args=""

# Needs pseudo-TTY for /bin/cat to hang around
docker_args+="-t"

# Detach so we can use docker exec to run stuff
docker_args+=" -d"

if [ -z "$USE_PIP_DOCKERS" ]; then
  # This is the home directory, but isn't really used directly in any scripts
  docker_homedir="/var/lib/jenkins"
  # The github repo will be cloned to workspace, and most scripts do most of
  # their work in here
  docker_workspace="$docker_homedir/workspace"
  # see note below
  docker_host_workspace="$docker_homedir/host-workspace"
  docker_user="-u jenkins"
else
  # The pip build script was originally written to mount /remote, to build all
  # the wheels into /wheelhouse, and then to copy only the finished wheels to
  # /remote.
  docker_homedir="/"
  docker_workspace="/remote"
  docker_host_workspace="/remote"
  docker_user=""
  docker_args+=" --ipc=host"
fi

# Mount the workspace to another location so we can copy files to it
# TODO this directory ($docker_homedir/host-workspace) doesn't actually seem to
# be used anywhere. This line still seems necessary just because it happens to
# create $docker_homedir when it creates $docker_homedir/host-workspace , and
# $docker_homedir is always needed. (On host-mount jobs $docker_homedir will
# always be created regardless of whether this line is here or not)
docker_args+=" -v $WORKSPACE:$docker_host_workspace"

# Prepare for capturing core dumps
mkdir -p $WORKSPACE/crash
docker_args+=" -v $WORKSPACE/crash:/var/crash"

if [ "$WORKSPACE_SOURCE" = "host-mount" ]; then
    # Directly mount host workspace into workspace directory.
    # This is "old-style" behavior
    docker_args+=" -v $WORKSPACE:$docker_workspace"
fi

# Working directory is homedir
docker_args+=" -w $docker_homedir"

if [ -n "${CUDA_VERSION:-}" ]; then
    # Extra arguments to use for nvidia-docker
    docker_args+=" --runtime=nvidia"

    # If CUDA_VERSION is equal to native, it it using one of the
    # nvidia/cuda images as base image and all CUDA related metadata
    # is embedded in the image itself.
    if [ "${CUDA_VERSION}" != "native" ]; then
      docker_args+=" -e CUDA_VERSION=${CUDA_VERSION}"
      docker_args+=" -e NVIDIA_VISIBLE_DEVICES=all"
    fi
fi

if [ -n "${CPU_PERF_TEST:-}" ] && [[ $(/bin/hostname) == *packet* ]]; then
  docker_args+=" --security-opt seccomp=$docker_homedir/allow_perf_event_open.json"
fi

if [[ $(/bin/hostname) == *-rocm-* ]]; then
  docker_args+=" --device=/dev/kfd --device=/dev/dri --group-add video"
fi

# Image
docker_args+=" ${DOCKER_IMAGE}"

# Sometimes, docker pull will fail with "TLS handshake timed out"
# or "unexpected EOF".  This usually indicates intermittent failure.
# Try again!
retry docker pull "${DOCKER_IMAGE}"

# We start a container and detach it such that we can run
# a series of commands without nuking the container
echo "Starting container for image ${DOCKER_IMAGE}"
if [ -n "${CPU_PERF_TEST:-}" ] && [[ $(/bin/hostname) == *packet* ]]; then
  id=$(/usr/bin/taskset -c 4-7 docker run ${docker_args} /bin/cat | tail -1)
else
  id=$(docker run ${docker_args} /bin/cat)
fi

trap "echo 'Stopping container...' &&
# Turn on GPU autoboost for GPU instances again
if [ -n \\"${CUDA_VERSION:-}\\" ]; then
    (sudo /usr/bin/set_gpu_autoboost_on.sh) || true;
fi &&
docker rm -f $id > /dev/null" EXIT

if [ "$WORKSPACE_SOURCE" = "host-copy" ]; then
    # Copy the workspace into the Docker image.
    # Pick this if you want the source code to persist into a saved
    # docker image
    docker cp $WORKSPACE/. "$id:$docker_workspace"
fi

if [ "$IMPORT_ENV" == 1 ]; then
    # Copy in the env file
    docker cp $WORKSPACE/env "$id:$docker_workspace/env"
fi

# I found the only way to make the command below return the proper
# exit code is by splitting run and exec. Executing run directly
# doesn't propagate a non-zero exit code properly.
(
    # Get into working dir, now that it exists
    echo "cd $docker_workspace"

    if [ "$IMPORT_ENV" == 1 ]; then
      # Source environment
      echo 'source ./env'
    fi

    # Override WORKSPACE environment variable. Every container build
    # uses /var/lib/jenkins/workspace for their $WORKSPACE and $PWD
    # instead of /var/lib/jenkins/workspace/some/build/name.
    # This might improve the sccache hit rate, if there is ever
    # a component of the hash key that depends on the file system
    # path (there shouldn't be, but you never know).
    echo 'declare -x WORKSPACE=$PWD'

    # Use everything below the '####' as script to run
    sed -n '/^####/ { s///; :a; n; p; ba; }' "${BASH_SOURCE[0]}"
) | docker exec $docker_user -i "$id" bash

if [ -n "${COMMIT_DOCKER_IMAGE:-}" ]; then
    sanitize_image_tag ${COMMIT_DOCKER_IMAGE}
    COMMIT_DOCKER_IMAGE=${SANITIZED_IMAGE}

    echo "Committing container state to ${COMMIT_DOCKER_IMAGE}..."
    docker commit "$id" "${COMMIT_DOCKER_IMAGE}" > "$output"
    retry docker push "${COMMIT_DOCKER_IMAGE}" > "$output"
    # There's no reason to keep this image around locally, so kill it
    docker rmi "${COMMIT_DOCKER_IMAGE}" > "$output"
fi

exit 0

#### SCRIPT TO RUN IN DOCKER CONTAINER BELOW THIS LINE
'''

    attrs.context.with {
      environmentVariables {
        env('DOCKER_IMAGE', attrs.image)
        env('COMMIT_DOCKER_IMAGE', attrs.getOrDefault("commitImage", ""))
        env('CUDA_VERSION', attrs.getOrDefault("cudaVersion", ""))
        // TODO: Consider using an enum here. Unfortunately, I don't know how to conveniently
        // ferry the result to java.
        env('WORKSPACE_SOURCE', attrs.getOrDefault("workspaceSource", "host-mount"))
        env('COPY_WORKSPACE', attrs.getOrDefault("copyWorkspace", ""))
        env('IMPORT_ENV', attrs.getOrDefault("importEnv", 1))
        env('USE_PIP_DOCKERS', attrs.getOrDefault("usePipDockers", ""))
      }

      // If we're using Amazon ECR then we can't use fixed credentials
      if (attrs.image.contains(".ecr.us-east-1.amazonaws.com")) {
        shell """#!/bin/bash
registry=\$(echo "\${DOCKER_IMAGE}" | awk -F/ '{print \$1}')
echo "Logging into \${registry}"
retry () {
    \$*  || (sleep 1 && \$*) || (sleep 2 && \$*)
}
do_login () {
  aws ecr get-authorization-token --region us-east-1 --output text --query 'authorizationData[].authorizationToken' |
    base64 -d |
    cut -d: -f2 |
    docker login -u AWS --password-stdin \${registry}
}
retry do_login
"""
      }

      // Optionally login with registry before doing anything
      def credentials = attrs.get("registryCredentials")
      if (credentials != null) {
        def username = credentials[0]
        def password = credentials[1]
        def registry = attrs.image.split('/').first()

        shell """#!/bin/bash
echo "Logging into ${registry}"
retry () {
    \$*  || (sleep 1 && \$*) || (sleep 2 && \$*)
}
do_login () {
  echo ${password} | docker login -u ${username} --password-stdin ${registry}
}
retry do_login
"""
      }

      shell(prefix + attrs.script)
    }
  }
}
