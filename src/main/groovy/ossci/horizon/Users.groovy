package ossci.tensorcomp

class Users {
  // Users that can ask @tensorcompbot to trigger build on behalf
  // of a user that is not an admin or present in the whitelist.
  static final List<String> githubAdmins = [
    "MisterTea",
    "kittipatv",
    "econti",
  ]

  // GitHub users for who pull request builds are automatically started.
  static final List<String> githubUserWhitelist = [
    "MisterTea",
    "kittipatv",
    "econti",
  ]
}
