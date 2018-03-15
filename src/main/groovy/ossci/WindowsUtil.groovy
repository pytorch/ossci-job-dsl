package ossci

class WindowsUtil {
  static void shell(
      context,
      String script,
      String cudaVersion) {
    String prefix = '''
# Helper to run snippet inside a Windows environment.

# ADDITIONAL SCRIPT TO RUN IN WINDOWS BELOW THIS LINE
'''

    context.with {
      environmentVariables {
        env(
          'CUDA_VERSION',
          cudaVersion
        )
      }

      shell(prefix + script)
    }
  }

  static String cudaOOMShutdownScript = '''
if (manager.build.result.toString().contains("FAILURE") || manager.build.result.toString().contains("ABORTED")) {
  def logLines = manager.build.logFile.readLines()
  def hasCudaOOMError = (logLines.count {it.contains("RuntimeError: cuda runtime error (2) : out of memory")} > 0)
  if (hasCudaOOMError) {
    Runtime runtime = Runtime.getRuntime()
    Process proc = runtime.exec("ls")
  }
}
'''
}
