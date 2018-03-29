package ossci.tensorcomp

class Users {
  // Users that can ask @tensorcompbot to trigger build on behalf
  // of a user that is not an admin or present in the whitelist.
  static final List<String> githubAdmins = [
    "prigoyal",
    "ftynse",
    "zdevito",
    "wsmoses",
    "nicolasvasilache",
    "ttheodor",
    "skimo-openhub",
    "orionr",
  ]

  // GitHub users for who pull request builds are automatically started.
  static final List<String> githubUserWhitelist = [
    "akhti",
    "doodlesbykumbi",
    "moskomule",
    "jekbradbury",
    "brettkoonce",
    "chr1sj0nes",
    "mingzhe09088",
    "salexspb",
    "apaszke",
    "ezyang",
    "orionr",
  ]
}
