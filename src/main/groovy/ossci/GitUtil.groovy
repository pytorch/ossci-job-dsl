package ossci

import javaposse.jobdsl.dsl.helpers.BuildParametersContext
import javaposse.jobdsl.dsl.helpers.scm.GitContext
import javaposse.jobdsl.dsl.helpers.scm.RemoteContext
import javaposse.jobdsl.dsl.helpers.step.StepContext
import javaposse.jobdsl.dsl.helpers.triggers.TriggerContext

class GitUtil {

  // Use this inside scm { git { ... } }
  static void defaultExtensions(GitContext context) {
    context.with {
      extensions {
        // NB: Not done here, instead we rely on JobUtil.common to take care of this for us.
        // They're the same thing: https://stackoverflow.com/questions/37540823/difference-between-delete-workspace-before-build-starts-and-wipe-out-reposito
        // rm -rf is actually pretty expensive
        // wipeOutWorkspace()
        cloneOptions {
          // Same as Travis default (NB: must set shallow,
          // otherwise depth has no effect.)
          shallow()
          depth(50)
        }
      }
    }
  }

  // Dead soon
  static void pullRequestRefspec(RemoteContext context) {
    context.with {
      refspec([
              // Fetch remote branches so we can merge the PR
              '+refs/heads/*:refs/remotes/origin/*',
              // Fetch PRs
              '+refs/pull/*:refs/remotes/origin/pr/*',
      ].join(' '))
    }
  }

  // dead soon
  static void githubPullRequestTrigger(TriggerContext context, users) {
    context.with {
      githubPullRequest {
        admins(users.githubAdmins)
        userWhitelist(users.githubUserWhitelist)
        useGitHubHooks()
      }
    }
  }

  // OTHER STUFF

  static void mergeStep(StepContext context) {
    context.with {
      // Merge GIT_COMMIT into GIT_MERGE_TARGET, if set
      shell '''
if [ -n "${GIT_MERGE_TARGET}" ]; then
  git branch -f merge-target ${GIT_MERGE_TARGET}
  git checkout -f merge-target
  git merge --no-ff ${GIT_COMMIT}
fi
'''
    }
  }

  static void resolveAndSaveParameters(StepContext context, String file) {
    context.with {
      // Capture the raw commit hash of GIT_COMMIT/GIT_MERGE_TARGET,
      // such that they can be passed to downstream builds without
      // risking that they are changed at the remote.
      shell """
rm -f "${file}"

# Expect the Git SCM plugin to have checked out the right commit if
# GIT_COMMIT is empty. Figure out what it points to so the commit hash
# can be passed to downstream builds.
if [ -z "\${GIT_COMMIT}" ]; then
  echo "GIT_COMMIT=\$(git rev-parse HEAD)" >> "${file}"
else
  echo "GIT_COMMIT=\$(git rev-parse "\${GIT_COMMIT}")" >> "${file}"
fi

if [ -n "\${GIT_MERGE_TARGET}" ]; then
  echo "GIT_MERGE_TARGET=\$(git rev-parse \${GIT_MERGE_TARGET})" >> "${file}"
  # NB: This logic is specific to pytorch/pytorch.
  # It's temporary; once we start mixing the repos together this
  # needs to accurately reflect folder structure.
  function changed() {
    git diff --name-only "\${GIT_MERGE_TARGET}" "\${GIT_COMMIT}"
  }
  if (changed | grep -q "^caffe2/"); then
    echo "CAFFE2_CHANGED=1" >> "${file}"
  else
    echo "CAFFE2_CHANGED=0" >> "${file}"
  fi
  # PyTorch is top-level, so anything that is not caffe2 is us
  if (changed | grep -qv "^caffe2/"); then
    echo "PYTORCH_CHANGED=1" >> "${file}"
  else
    echo "PYTORCH_CHANGED=0" >> "${file}"
  fi
else
  # Assume the worst
  echo "CAFFE2_CHANGED=1" >> "${file}"
  echo "PYTORCH_CHANGED=1" >> "${file}"
fi
"""
    }
  }
}
