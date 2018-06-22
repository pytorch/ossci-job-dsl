// If you want, you can define your seed job in the DSL and create it via the REST API.
// See https://github.com/sheehan/job-dsl-gradle-example#rest-api-runner

job('seed') {
  parameters {
    stringParam(
      'GIT_COMMIT',
      'origin/master',
      'Refspec of commit to use (e.g. origin/master)',
    )
  }
  scm {
    git {
      // Note: pytorchbot doesn't have admin access to this repo so you
      // have to add the webhook for the GitHub plugin yourself.
      remote {
        github('pytorch/ossci-job-dsl', 'ssh')
        credentials('pytorchbot')
      }
      branch('${GIT_COMMIT}')
    }
  }
  triggers {
    githubPush()
  }
  steps {
    dsl {
      external 'jobs/*.groovy'
      additionalClasspath 'src/main/groovy'
    }
  }
}
