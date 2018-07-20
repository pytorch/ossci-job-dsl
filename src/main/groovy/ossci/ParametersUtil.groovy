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

  static void UPLOAD_PACKAGE(BuildParametersContext context, defaultValue = false) {
    context.with {
      booleanParam(
          'UPLOAD_PACKAGE',
          defaultValue,
          "Check this to upload the finished package to the public"
      )
    }
  }

  static void CONDA_PACKAGE_NAME(BuildParametersContext context, defaultValue = '') {
    context.with {
      stringParam(
          'CONDA_PACKAGE_NAME',
          defaultValue,
          "The name of the package to upload to Anaconda"
      )
    }
  }

  static void PACKAGE_VERSION(BuildParametersContext context, defaultValue = '1000') {
    context.with {
      stringParam(
          'PACKAGE_VERSION',
          defaultValue,
          "The version to have in 0.4.1.dev#here#"
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
