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

  static void HYPOTHESIS_SEED(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'HYPOTHESIS_SEED',
          defaultValue,
          'Random seed that the Hypothesis testing library should use',
      )
    }
  }

  static void GITHUB_ORG(BuildParametersContext context, defaultValue = 'pytorch') {
    context.with {
      stringParam(
          'GITHUB_ORG',
          defaultValue,
          'The xxxx of https://github.com/xxxx/pytorch.git to build wheels for'
      )
    }
  }

  static void PYTORCH_BRANCH(BuildParametersContext context, defaultValue = 'v0.4.1') {
    context.with {
      stringParam(
          'PYTORCH_BRANCH',
          defaultValue,
          'Branch of $GITHUB_ORG/pytorch repo to checkout to build wheels for'
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

  static void USE_DATE_AS_VERSION(BuildParametersContext context, defaultValue = true) {
    context.with {
      booleanParam(
          'USE_DATE_AS_VERSION',
          defaultValue,
          "Check this to use the current date (on the executing machine) as the version"
      )
    }
  }

  static void VERSION_POSTFIX(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'VERSION_POSTFIX',
          defaultValue,
          "A string to be added as-is to the end of the version. Will get overwritten by OVERRIDE_PACKAGE_VERSION. Good to be used with USE_DATE_AS_VERSION"
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

  static void FULL_CAFFE2(BuildParametersContext context, defaultValue = false) {
    context.with {
      booleanParam(
          'FULL_CAFFE2',
          defaultValue,
          "Check this to build with FULL_CAFFE2=1 or not"
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
