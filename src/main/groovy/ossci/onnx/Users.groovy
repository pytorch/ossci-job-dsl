package ossci.onnx

import ossci.pytorch.Users

class Users {
  // GitHub users that can authorize builds
  static final List<String> githubAdmins = [
    "pietern",
    "ezyang",
    "bddppq",
    "houseroad",
    "orionr",
    "SsnL",
  ] + ossci.pytorch.Users.githubAdmins

  // GitHub users for who builds automatically run
  static final List<String> githubUserWhitelist = [
    "bddppq",
    "dzhulgakov",
    "ebarsoum",
    "ezyang",
    "gramalingam",
    "gchanan",
    "guschmue",
    "hmansell",
    "houseroad",
    "jamesr66a",
    "jerryzh168",
    "jywumsft",
    "killeent",
    "linkerzhang",
    "orionr",
    "prasanthpul",
    "prigoyal",
    "smessmer",
    "slbird",
    "soumith",
    "Yangqing",
    "yuanbyu",
    "zdevito",
    "anderspapitto",
    "yinghai",

    // these are bot users
    "onnxbot",
    "onnxbot-worker-1",
    "onnxbot-worker-2",
    "onnxbot-worker-3",
    "o8ht88z00f",
  ] + ossci.pytorch.Users.githubUserWhitelist
}
