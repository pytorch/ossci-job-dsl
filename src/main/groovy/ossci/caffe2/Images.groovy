package ossci.caffe2

class Images {

  /////////////////////////////////////////////////////////////////////////////
  // Docker images
  /////////////////////////////////////////////////////////////////////////////
  // NOTE this is the list of dockerBaseImages, but not the list of all Jenkins
  // build jobs that use Docker. The real source of all possible jenkins jobs
  // is the keys of the map
  //   baseImageOf<jenkinsBuildName, dockerImageName>
  // that is defined below
  static final List<String> dockerBaseImages = [
    // Primary builds
    'py2-cuda8.0-cudnn7-ubuntu16.04',
    'py2-cuda9.0-cudnn7-ubuntu16.04',
    'py2-cuda9.1-cudnn7-ubuntu16.04',
    'py2-gcc5-ubuntu16.04',
    'py2-mkl-ubuntu16.04',

    // Primary builds (Python 3.5, available by default on Ubuntu)
    'py3.5-cuda8.0-cudnn7-ubuntu16.04',
    'py3.5-cuda9.0-cudnn7-ubuntu16.04',
    'py3.5-cuda9.1-cudnn7-ubuntu16.04',
    'py3.5-gcc5-ubuntu16.04',
    'py3.5-mkl-ubuntu16.04',

    // Python compatibility (Python 3.6 is not default)
    'py3.6-gcc5-ubuntu16.04',
    'py3.6-clang6.0-ubuntu16.04',
    'py3.6-clang7-ubuntu16.04',

    // Compatibility check for cuDNN 6 and 5 (build only)
    'py2-cuda8.0-cudnn6-ubuntu16.04',
    // EOLed on 2018-06-26 by Yangqing, Pieter, Mingzhe
    // 'py2-cuda8.0-cudnn5-ubuntu16.04',

    // Compiler compatibility for 14.04 (build only)
    'py2-gcc4.8-ubuntu14.04',
    'py2-gcc4.9-ubuntu14.04',

    // Compiler compatibility for 16.04 (build only)
    'py2-gcc6-ubuntu16.04',
    'py2-gcc7-ubuntu16.04',
    'py2-clang3.8-ubuntu16.04',
    'py2-clang3.9-ubuntu16.04',
    'py2-clang4.0-ubuntu16.04',
    'py2-clang5.0-ubuntu16.04',
    'py2-clang6.0-ubuntu16.04',
    'py2-clang7-ubuntu16.04',

    // Python 3 compiler compatibility
    'py3.5-gcc6-ubuntu16.04',
    'py3.5-gcc7-ubuntu16.04',
    'py3.5-clang3.8-ubuntu16.04',
    'py3.5-clang3.9-ubuntu16.04',
    'py3.5-clang4.0-ubuntu16.04',
    'py3.5-clang5.0-ubuntu16.04',

    // Build for Android
    'py2-android-ubuntu16.04',

    // Builds for Anaconda
    'conda2-ubuntu16.04',
    'conda2-cuda9.0-cudnn7-ubuntu16.04',
    'conda2-cuda8.0-cudnn7-ubuntu16.04',
    'conda3-ubuntu16.04',
    'conda3-cuda9.0-cudnn7-ubuntu16.04',
    'conda3-cuda8.0-cudnn7-ubuntu16.04',

    'conda2-gcc4.8-ubuntu16.04',
    'conda3-gcc4.8-ubuntu16.04',

    // CentOS images (Python 2 only)
    'py2-centos7',
    'py2-cuda8.0-cudnn7-centos7',
    'py2-cuda9.0-cudnn7-centos7',

    // AMD ROCM builds
    'py2-clang7-rocmdeb-ubuntu16.04',
    'py2-devtoolset7-rocmrpm-centos7.5',
  ];

  /////////////////////////////////////////////////////////////////////////////
  // Map (BUILD_ENVIRONMENT == jenkins build name) --> dockerImage
  static final Map<String, String> baseImageOf = [:]
  static {
    // By default populated by all dockerBaseImages pointing to themselves
    for (String baseImage : dockerBaseImages) {
      baseImageOf.put(baseImage, baseImage);
    }

    //////////////////////////////////////////////////////////////////////////
    // Additional builds that re-use dockerBaseImages below

    // Aten builds
    baseImageOf.put("py2-mkl-aten-ubuntu16.04", "py2-mkl-ubuntu16.04")
    baseImageOf.put("py2-cuda8.0-cudnn7-aten-ubuntu16.04", "py2-cuda8.0-cudnn7-ubuntu16.04")
    baseImageOf.put("py2-cuda9.0-cudnn7-aten-ubuntu16.04", "py2-cuda9.0-cudnn7-ubuntu16.04")

    // environment that is used to run pytorch->onnx->caffe2 integration tests
    baseImageOf.put("onnx-py2-gcc5-ubuntu16.04", "py2-gcc5-ubuntu16.04")

    // Verify that all docker images (values) in the map are valid
    for (String baseImage : baseImageOf.values()) {
      assert baseImage in dockerBaseImages
    }
    // Verify that all base images are in the map
    for (String baseImage : dockerBaseImages) {
      assert baseImage in baseImageOf.keySet()
    }
  }

  // Actual list of all Docker jenkins-builds that will be defined
  static final Collection<String> allDockerBuildEnvironments = baseImageOf.keySet();

  static final Collection<String> dockerCaffe2CondaBuildEnvironments;
  static {
    dockerCaffe2CondaBuildEnvironments = allDockerBuildEnvironments
          .stream()
          .filter { buildEnv -> buildEnv.startsWith("conda") }
          .collect();
  }

  // Pip, conda, and libtorch packages
  static final List<String> packagesDockerBaseImages = [
    "manylinux-cuda80",
    "manylinux-cuda90",
    "manylinux-cuda92",
    "conda-cuda"
  ];

  static final List<String> dockerPipBuildEnvironments = [
    'linux_manywheel_2.7m_cpu',
    'linux_manywheel_2.7mu_cpu',
    'linux_manywheel_3.5m_cpu',
    'linux_manywheel_3.6m_cpu',
    'linux_manywheel_3.7m_cpu',

    'linux_manywheel_2.7m_cu80',
    'linux_manywheel_2.7mu_cu80',
    'linux_manywheel_3.5m_cu80',
    'linux_manywheel_3.6m_cu80',
    'linux_manywheel_3.7m_cu80',

    'linux_manywheel_2.7m_cu90',
    'linux_manywheel_2.7mu_cu90',
    'linux_manywheel_3.5m_cu90',
    'linux_manywheel_3.6m_cu90',
    'linux_manywheel_3.7m_cu90',

    'linux_manywheel_2.7m_cu92',
    'linux_manywheel_2.7mu_cu92',
    'linux_manywheel_3.5m_cu92',
    'linux_manywheel_3.6m_cu92',
    'linux_manywheel_3.7m_cu92',
  ];

  static final List<String> macPipBuildEnvironments = [
    'macos_wheel_2.7_cpu',
    'macos_wheel_3.5_cpu',
    'macos_wheel_3.6_cpu',
    'macos_wheel_3.7_cpu',
  ];

  static final List<String> dockerLibtorchBuildEnvironments = [
    'linux_libtorch_2.7m_cpu',
    'linux_libtorch_2.7m_cu80',
    'linux_libtorch_2.7m_cu90',
    'linux_libtorch_2.7m_cu92',
  ];

  static final List<String> macLibtorchBuildEnvironments = [
    'macos_libtorch_2.7m_cpu',
  ];

  static final List<String> dockerCondaBuildEnvironments = [
    'linux_conda_2.7_cpu',
    'linux_conda_3.5_cpu',
    'linux_conda_3.6_cpu',
    'linux_conda_3.7_cpu',

    'linux_conda_2.7_cu80',
    'linux_conda_3.5_cu80',
    'linux_conda_3.6_cu80',
    'linux_conda_3.7_cu80',

    'linux_conda_2.7_cu90',
    'linux_conda_3.5_cu90',
    'linux_conda_3.6_cu90',
    'linux_conda_3.7_cu90',

    'linux_conda_2.7_cu92',
    'linux_conda_3.5_cu92',
    'linux_conda_3.6_cu92',
    'linux_conda_3.7_cu92',
  ];

  static final List<String> macCondaBuildEnvironments = [
    'macos_conda_2.7_cpu',
    'macos_conda_3.5_cpu',
    'macos_conda_3.6_cpu',
    'macos_conda_3.7_cpu',
  ];

  static final List<String> allNightlyBuildEnvironments = dockerCondaBuildEnvironments + dockerPipBuildEnvironments + macCondaBuildEnvironments + macPipBuildEnvironments;


  ///////////////////////////////////////////////////////////////////////////////
  // Mac environments
  ///////////////////////////////////////////////////////////////////////////////

  static final List<String>  macOsBuildEnvironments = [
    // Basic macOS builds
    'py2-system-macos10.13',
    'py2-brew-macos10.13',

    // iOS builds (hosted on macOS)
    // No need for py2/py3 since we don't care about Python for iOS build
    'py2-ios-macos10.13',

    // Anaconda build environments
    'conda3-macos10.13',
  ];

  // macOs conda-builds referred to by the nightly upload job
  // These jobs are actually defined along with the rest of the
  // macOsBuildEnvironments above
  static final List<String>  macCaffe2CondaBuildEnvironments = [
    'conda2-macos10.13',
    'conda3-macos10.13',
  ];


  ///////////////////////////////////////////////////////////////////////////////
  // PR environments
  ///////////////////////////////////////////////////////////////////////////////

  static final List<String> buildAndTestEnvironments = [
    // These jobs are now running on CircleCI
    // 'py2-cuda8.0-cudnn6-ubuntu16.04',
    // 'py2-cuda9.0-cudnn7-ubuntu16.04',
    // 'py2-cuda9.1-cudnn7-ubuntu16.04',
    // 'py2-mkl-ubuntu16.04',

    // Vanilla Ubuntu 16.04 (Python 2/3)
    // 'onnx-py2-gcc5-ubuntu16.04',
    // //'py3-gcc5-ubuntu16.04',

    // Vanilla Ubuntu 14.04
    // 'py2-gcc4.8-ubuntu14.04',

    'py2-clang7-rocmdeb-ubuntu16.04',
    'py2-devtoolset7-rocmrpm-centos7.5',
  ];

  static final List<String> buildOnlyEnvironments = [
    // These jobs are now running on CircleCI
    // // Compatibility check for CUDA 8 / cuDNN 7 (build only)
    // 'py2-cuda8.0-cudnn7-ubuntu16.04',
    // // 'py2-cuda8.0-cudnn5-ubuntu16.04',

    // // Compiler compatibility for 14.04 (build only)
    // // Should be covered by pytorch-linux-trusty-py3.6-gcc4.8
    // //'py2-gcc4.9-ubuntu14.04',

    // // Compiler compatibility for 16.04 (build only)
    // 'py2-clang3.8-ubuntu16.04',
    // 'py2-clang3.9-ubuntu16.04',
    // // 'py2-gcc6-ubuntu16.04',
    // // should be covered by pytorch-linux-trusty-py3.6-gcc7
    // //'py2-gcc7-ubuntu16.04',

    // // Build for Android
    // 'py2-android-ubuntu16.04',

    // // Run a CentOS build (verifies compatibility with CMake 2.8.12)
    // 'py2-cuda9.0-cudnn7-centos7',

    // // Build for iOS
    // 'py2-ios-macos10.13',

    // // macOS builds
    // 'py2-system-macos10.13',

    // Windows builds
    // The python part is actually ignored by build_windows.bat
    'py2-cuda9.0-cudnn7-windows',
  ];


  /////////////////////////////////////////////////////////////////////////////
  // The windows environment
  /////////////////////////////////////////////////////////////////////////////

  static final List<String>  windowsBuildEnvironments = [
    'py2-cuda9.0-cudnn7-windows'
  ];


  /////////////////////////////////////////////////////////////////////////////
  // Integrated environments
  /////////////////////////////////////////////////////////////////////////////
  static final List<String> integratedEnvironments = [
      // These jobs are now running on CircleCI
      // 'onnx-py2-gcc5-ubuntu16.04',
      // // 'py3.6-gcc5-ubuntu16.04',
  ];



}
