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

  static void UPLOAD_TO_CONDA(BuildParametersContext context, defaultValue = false) {
    context.with {
      booleanParam(
          'UPLOAD_TO_CONDA',
          defaultValue,
          "Check this to upload the finished package to Anaconda.org"
      )
    }
  }

  static void CONDA_PACKAGE_NAME(BuildParametersContext context, defaultValue = 'caffe2') {
    context.with {
      stringParam(
          'CONDA_PACKAGE_NAME',
          defaultValue,
          "The name of the package to upload to Anaconda"
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
}
