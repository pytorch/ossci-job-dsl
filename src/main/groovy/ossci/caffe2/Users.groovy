package ossci.caffe2

class Users {
  // GitHub users that can authorize builds.
  static final List<String> githubAdmins = [
    'ajtulloch', // Andrew Tulloch
    'andrewwdye', // Andrew Dye
    'asaadaldien', // Ahmed S. Taei
    'BIT-silence', // Xiaomeng Yang
    'bddppq', // bddppq
    'bwasti', // Bram Wasti
    'dzhulgakov', // Dmytro Dzhulgakov
    'enosair', // Qinqing Zheng
    'ezyang', // Edward Yang
    'harouwu', // null
    'heslami', // null
    'houseroad', // Lu Fang
    'hlu1', // null
    'ilia-cher', // null
    'jaeyounkim', // Jaeyoun Kim
    'jamesr66a', // James Reed
    'jerryzh168', // Jerry Zhang
    'kuttas', // null
    'Maratyszcza', // Marat Dukhan
    'mfawzymkh', // Mohamed Fawzy
    'MisterTea', // Jason Gauci
    'orionr', // Orion Reblitz-Richardson
    'pietern', // Pieter Noordhuis
    'pjh5', // Paul Jesse Hellemn
    'romain-intel', // Romain
    'salexspb', // Alexander Sidorov
    'smessmer', // Sebastian Messmer
    'sf-wind', // null
    'sunwael', // Wael Abdelghani
    'wesolwsk', // Lukasz Wesolowski
    'Yangqing', // Yangqing Jia
    'zem7', // Mohammad Hossain
  ]

  // GitHub users for who builds automatically run
  static final List<String> githubUserWhitelist = [
    "slayton58",
    "ezyang",
    "zdevito",
    "houseroad",
    "prigoyal",
    "christoph-conrads",
    "pjh5",
    "akyrola",
    "anderspapitto",
    "orionr",
    "yinghai",

    // these are bot users
    "onnxbot",
    "onnxbot-worker-1",
    "onnxbot-worker-2",
    "onnxbot-worker-3",
    "o8ht88z00f",
  ]
}
