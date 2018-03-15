package ossci

import javaposse.jobdsl.dsl.helpers.step.PhaseJobContext

class PhaseJobUtil {
  // Adds a condition to the execution of this job. Here's what the Jenkins UI
  // has to say about it:
  //
  //    Add very simple and basic condition to run this job. Write a condition in groovy script format. You
  //    can use environment variable. If the expression cannot be evaluate, false is assumed.
  //
  //    Example:
  //      ${BUILDNUMBER} % 2 == 1
  //        for running this job every two times or if you have a string parameter you can use "${Name}" == "Myjob".
  static void condition(PhaseJobContext context, String cond) {
    context.with {
      configure { node ->
        (node / 'enableCondition').setValue("true");
        (node / 'condition').setValue(cond);
      }
    }
  }
}
