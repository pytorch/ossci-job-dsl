package ossci

import javaposse.jobdsl.dsl.helpers.BuildParametersContext

/**
 * Helper functions to use in 'parameters' contexts (in 'job'/'multiJob'/etc)
 */
class ParametersUtil {
  static void RUN_DOCKER_ONLY(BuildParametersContext context, defaultValue = false) {
    context.with {
      booleanParam(
          'RUN_DOCKER_ONLY',
          defaultValue,
          'Run only Docker jobs (this is used by the Docker build)'
      )
    }
  }

  static void DOCKER_IMAGE_TAG(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'DOCKER_IMAGE_TAG',
          defaultValue,
          'Tag of Docker image to use in downstream builds',
      )
    }
  }

  static void CAFFE2_DOCKER_IMAGE_TAG(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'CAFFE2_DOCKER_IMAGE_TAG',
          defaultValue,
          'Tag of Caffe2 Docker image to use in downstream builds',
      )
    }
  }

  static void CMAKE_ARGS(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'CMAKE_ARGS',
          defaultValue,
          'Additional CMake arguments',
      )
    }
  }

  static void EXTRA_CAFFE2_CMAKE_FLAGS(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'EXTRA_CAFFE2_CMAKE_FLAGS',
          defaultValue,
          'Additional arguments passed to build_pytorch_libs::build_caffe2 (all flags that need to hit root-level-cmake)',
      )
    }
  }

  static void RUN_TEST_PARAMS(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'RUN_TEST_PARAMS',
          defaultValue,
          'Passed to run_test.py as is. e.g. "-i c10d -v" for only c10d tests with verbose',
      )
    }
  }

  static void HYPOTHESIS_SEED(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'HYPOTHESIS_SEED',
          defaultValue,
          'Random seed that the Hypothesis testing library should use',
      )
    }
  }

  static void PYTORCH_REPO(BuildParametersContext context, defaultValue = 'pytorch') {
    context.with {
      stringParam(
          'PYTORCH_REPO',
          defaultValue,
          'The xxxx of https://github.com/xxxx/pytorch.git to build wheels for'
      )
    }
  }

  static void PYTORCH_BRANCH(BuildParametersContext context, defaultValue = 'master') {
    context.with {
      stringParam(
          'PYTORCH_BRANCH',
          defaultValue,
          'Branch of $PYTORCH_REPO/pytorch repo to checkout to build wheels for. This can also be a commit hash. It will be passed straight into "git checkout <>"'
      )
    }
  }

  static void UPLOAD_PACKAGE(BuildParametersContext context, defaultValue = false) {
    context.with {
      booleanParam(
          'UPLOAD_PACKAGE',
          defaultValue,
          "Check this to upload the finished package to the public"
      )
    }
  }

  static void OVERRIDE_PACKAGE_VERSION(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'OVERRIDE_PACKAGE_VERSION',
          defaultValue,
          "The exact entier package version string to use, overriding all other version parameters"
      )
    }
  }

  static void PYTORCH_BUILD_VERSION(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'PYTORCH_BUILD_VERSION',
          defaultValue,
          "PYTORCH_BUILD_VERSION as conda/build_pytorch.sh expects it"
      )
    }
  }

  static void PYTORCH_BUILD_NUMBER(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'PYTORCH_BUILD_NUMBER',
          defaultValue,
          "PYTORCH_BUILD_NUMBER as conda/build_pytorch.sh expects it"
      )
    }
  }

  static void TORCH_PACKAGE_NAME(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'TORCH_PACKAGE_NAME',
          defaultValue,
          "The name of the package to upload"
      )
    }
  }

  static void PIP_UPLOAD_FOLDER(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'PIP_UPLOAD_FOLDER',
          defaultValue,
          "The folder to upload to. Unset ('') for the default, 'nightly/' for nightly jobs"
      )
    }
  }

  static void TORCH_CONDA_BUILD_FOLDER(BuildParametersContext context, defaultValue = 'pytorch-nightly') {
    context.with {
      stringParam(
          'TORCH_CONDA_BUILD_FOLDER',
          defaultValue,
          "The build-folder to call conda-build on."
      )
    }
  }

  static void DEBUG(BuildParametersContext context, defaultValue = false) {
    context.with {
      booleanParam(
          'DEBUG',
          defaultValue,
          "Check this to print with Debug release mode"
      )
    }
  }

  static void GIT_COMMIT(BuildParametersContext context, String defaultValue = 'origin/master') {
    context.with {
      stringParam(
          'GIT_COMMIT',
          defaultValue,
          'Refspec of commit to use (e.g. origin/master)',
      )
    }
  }

  static void COMMIT_SOURCE(BuildParametersContext context, String defaultValue = '') {
    context.with {
      stringParam(
          'COMMIT_SOURCE',
          defaultValue,
          'Source of the commit (master or pull-request)',
      )
    }
  }

  static void GIT_MERGE_TARGET(BuildParametersContext context, String defaultValue = '') {
    context.with {
      stringParam(
          'GIT_MERGE_TARGET',
          defaultValue,
          'Refspec of commit to merge GIT_COMMIT into (e.g. origin/master)',
      )
    }
  }

  static void GITHUB_REPO(BuildParametersContext context, String defaultValue = 'you-forgot-to-set-parameter/GITHUB_REPO') {
    context.with {
      stringParam(
          'GITHUB_REPO',
          defaultValue,
          'GitHub repository to get commits from (e.g. pytorch/pytorch)',
      )
    }
  }

}
