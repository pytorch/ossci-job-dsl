// This file is manually managed
//
// WARNING: THIS FILE IS OBSOLETE
// For the new location check
// https://github.com/pytorch/pytorch/blob/4dce482acb2f0b248e4886b3069dca8e3a1b7681/.circleci/config.yml#L5787
package ossci.pytorch
class DockerVersion {
  // WARNING: if you change this to a new version number,
  // you **MUST** also add that version number to the allDeployedVersions list below
  static final String version = "05efe7b7cf0d2b143b09ce3d9ab0fc6724e397ce";

  // NOTE: this is a comma-separated list. E.g. "262,220,219"
  static final String allDeployedVersions = "271,262,256,278,282,291,300,323,327,347,389,401,402,403,405";
}
