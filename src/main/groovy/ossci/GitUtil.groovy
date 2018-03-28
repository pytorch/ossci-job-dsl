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
  git reset --hard ${GIT_COMMIT}
  git merge --no-ff ${GIT_MERGE_TARGET} || git merge --abort
fi
'''
    }
  }

  static void resolveAndSaveParameters(StepContext context, String file) {
    context.with {
      // Capture the raw commit hash of GIT_COMMIT/GIT_MERGE_TARGET,
      // such that they can be passed to downstream builds without
      // risking that they are changed at the remote.
      shell """#!/bin/bash

set -ex

rm -f "${file}" || true

# Expect the Git SCM plugin to have checked out the right commit if
# GIT_COMMIT is empty. Figure out what it points to so the commit hash
# can be passed to downstream builds.
if [ -z "\${GIT_COMMIT}" ]; then
  echo "GIT_COMMIT=\$(git rev-parse HEAD)" >> "${file}"
else
  echo "GIT_COMMIT=\$(git rev-parse \${GIT_COMMIT})" >> "${file}"
fi

if [ -n "\${GIT_MERGE_TARGET}" ]; then
  echo "GIT_MERGE_TARGET=\$(git rev-parse \${GIT_MERGE_TARGET})" >> "${file}"

  if [ test -x .jenkins/pytorch/changed.sh ]; then
    if .jenkins/pytorch/dirty.sh "\${GIT_MERGE_TARGET}" "\${GIT_COMMIT}"; then
      echo "PYTORCH_CHANGED=1" >> "${file}"
    else
      echo "PYTORCH_CHANGED=0" >> "${file}"
    fi
  else
    echo "PYTORCH_CHANGED=1" >> "${file}"
  fi

  if [ test -x .jenkins/caffe2/changed.sh ]; then
    if .jenkins/caffe2/dirty.sh "\${GIT_MERGE_TARGET}" "\${GIT_COMMIT}"; then
      echo "CAFFE2_CHANGED=1" >> "${file}"
    else
      echo "CAFFE2_CHANGED=0" >> "${file}"
    fi
  else
    echo "CAFFE2_CHANGED=1" >> "${file}"
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
