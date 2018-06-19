package ossci

import javaposse.jobdsl.dsl.Job
import javaposse.jobdsl.dsl.jobs.MultiJob

class JobUtil {
  // These are job configuration which should be applied to all jobs, unless
  // you have a really good reason
  static void common(Job context, String labelExpression) {
    context.with {
      timeoutAndFailAfter(context, 90)
      label(labelExpression)
      concurrentBuild()
      publishers {
        publishBuild {
          // 30 was determined by looking at how many days of builds we were carrying
          // when we last ran out of space, and then halving it
          discardOldBuilds(30)
        }
      }
      wrappers {
        preBuildCleanup()
        timestamps()
      }
    }
  }

  // This is the default job configuration for trigger jobs.  These
  // jobs have to do a git clone, checkout and merge, so they should be run
  // on a machine with good IO and network performance
  static void commonTrigger(Job context) {
    context.with {
      common delegate, 'trigger'
      // Trigger jobs can be blocked due to queueing issues, so give
      // them a much longer timeout
      timeoutAndFailAfter(context, 1440)
    }
  }

  // Sets up GitHub SCM appropriate for invocation from master or pull request.
  // Usually called from downstream jobs that actuall do work.
  // This requires GIT_COMMIT parameter to be set.
  //
  // TODO: The refspec isn't that efficient, as we will be forced to pull references
  // for ALL prs (of which there will be a lot at any given point in time.)
  static void gitCommitFromPublicGitHub(Job context, String ownerAndProject) {
    context.with {
      scm {
        git {
          remote {
            github(ownerAndProject)
            refspec([
                // Fetch all branches
                '+refs/heads/*:refs/remotes/origin/*',
                // Fetch PRs so we can trigger from pytorch-pull-request
                '+refs/pull/*:refs/remotes/origin/pr/*',
            ].join(' '))
          }
          branch('${GIT_COMMIT}')
          GitUtil.defaultExtensions(delegate)
        }
      }
    }
  }

  static void timeoutAndFailAfter(Job context, int minutes) {
    context.with {
      wrappers {
        timeout {
          absolute(minutes)
          failBuild()
          writeDescription('Build failed due to timeout after {0} minutes')
        }
      }
    }
  }

  // Sets up a job to report commit status to GitHub.  You need to make
  // sure some ghprb env vars get set for this to work.  Check e.g.
  // caffe2-pull-request for how it passes parameters to downstream
  // jobs to make this work.
  static void subJobDownstreamCommitStatus(Job ctx, String reportAs) {
    ctx.with {
      wrappers {
        downstreamCommitStatus {
          context("pr/${reportAs}")

          // To prevent informing the build when it is triggered, you can
          // set the '--none--' constant; but I think we actually want to
          // see when the trigger occurs
          triggeredStatus('Build is triggered')

          // Only inform when build is started...
          startedStatus('Build in progress')

          // Posted upon completion of this job
          completedStatus('SUCCESS', 'Build successful')
          completedStatus('FAILURE', 'Build failed')
          completedStatus('PENDING', 'Build pending')
          completedStatus('ERROR', 'Build errored')
        }
      }
    }
  }

  static void masterTrigger(MultiJob context, String ownerAndProject, String branchName, boolean triggerOnPush = true) {
    context.with {
      commonTrigger(delegate)
      scm {
        git {
          remote {
            github(ownerAndProject)
            refspec('+refs/heads/' + branchName + ':refs/remotes/origin/' + branchName)
          }
          branch('origin/' + branchName)
          GitUtil.defaultExtensions(delegate)
        }
      }
      if (triggerOnPush) {
        triggers {
          githubPush()
        }
      }
    }
  }

  // This configures a GitHub job to handle GitHub pull requests under multiple
  // configurations (thus it's MultiJob only; DON'T use this for a freestyle
  // job, it's not setup to do that).
  static void gitHubPullRequestTrigger(MultiJob context, String ownerAndProject, String githubAuthIdValue, Class users, boolean reportStatus = false) {
    context.with {
      parameters {
        // This defaults to ${ghprbActualCommit} so that the Git SCM plugin
        // will check out the HEAD of the pull request. Another parameter emitted
        // by the GitHub pull request builder plugin is ${sha1}, which points to
        // origin/pulls/1234/merge if and only if the PR could be merged to master.
        // This ref changes whenever anything is committed to master.
        // We use a multi job which triggers builds at different times.
        // Following this ref blindly can therefore lead to a single build
        // using different commits. To avoid this, we use ${ghprbActualCommit},
        // perform the merge to master ourselves, and capture the commit hash of
        // origin/master in this build. The raw version of this commit hash (and
        // not origin/master) is then propagated to downstream builds.
        ParametersUtil.GIT_COMMIT(delegate, '${ghprbActualCommit}')
        ParametersUtil.GIT_MERGE_TARGET(delegate, 'origin/${ghprbTargetBranch}')
      }
      commonTrigger(delegate)
      scm {
        git {
          remote {
            github(ownerAndProject)
            refspec([
                // Fetch remote branches so we can merge the PR
                '+refs/heads/*:refs/remotes/origin/*',
                // Fetch PRs
                '+refs/pull/*:refs/remotes/origin/pr/*',
            ].join(' '))
          }
          branch('${GIT_COMMIT}')
          GitUtil.defaultExtensions(delegate)
        }
      }

      triggers {
        githubPullRequest {
          admins(users.githubAdmins)
          // userWhitelist(users.githubUserWhitelist)
          // Below: An experiment in LIVING DANGEROUSLY!
          permitAll()
          useGitHubHooks()
          // If labeled with skip-tests, don't run tests.  This is
          // currently only being used by caffe2
          blackListLabels(['skip-tests'])

          // Only build the PR if it targets 'master'
          // 'sending_pr' is a special case for ROCmSoftwarePlatform/pytorch
          whiteListTargetBranches(['master', 'fbsync', 'sending_pr'])

          // It would be nice to require the CLA Signed label,
          // but as the label is added by the facebook github bot,
          // it races with the trigger sent to Jenkins.
          // To trigger builds immediately upon creating a PR,
          // we'll leave this disabled.
          //whiteListLabels(['CLA Signed'])
        }
      }

      // The configure block gives access to the raw XML.
      // This is needed here because the ghprb DSL extension doesn't
      // provide access to the credentials to use.
      configure { node ->
        def triggers = node / 'triggers'
        // Iterate and find the right trigger node so that this
        // doesn't depend on the version of the ghprb plugin.
        triggers.children().each { trigger ->
          if (trigger.name() == 'org.jenkinsci.plugins.ghprb.GhprbTrigger') {
            // This adds the <gitHubAuthId/> tag with the pytorchbot credentials
            def gitHubAuthId = trigger / 'gitHubAuthId'
            gitHubAuthId.setValue(githubAuthIdValue)

            def extensions = trigger / 'extensions'
            if (!reportStatus) {
              // Replace default extension with a single one that
              // instructs ghprb to not set any commit status.
              // We rely on the downstream jobs to do this.
              def statusNode = { "org.jenkinsci.plugins.ghprb.extensions.status.${it}" }
              extensions.remove(extensions / statusNode('GhprbSimpleStatus'))
              extensions / statusNode('GhprbNoCommitStatus')
            }

            // Cancel PR builds when there is an update
            def buildNode = { "org.jenkinsci.plugins.ghprb.extensions.build.${it}" }
            extensions / buildNode('GhprbCancelBuildsOnUpdate')
          }
        }
      }
    }
  }
}
