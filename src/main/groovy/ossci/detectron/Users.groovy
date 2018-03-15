package ossci.detectron

class Users {
  // GitHub users that can authorize builds.
  static final List<String> githubAdmins = [
    'pietern', // Pieter Noordhuis
    'rbgirshick', // Ross
    'ir413', // Ilija
  ]

  // GitHub users for who builds automatically run
  static final List<String> githubUserWhitelist = [
  ]
}
