package ossci

class EmailUtil {
  static String sendEmailScript = '''
import javax.mail.*
import javax.mail.internet.*

import java.util.Properties
import javax.mail.Authenticator
import javax.mail.PasswordAuthentication
import javax.mail.Session

def sendEmail(receivers, subject, text) {
  def fromEmail = "jenkins.ossci@gmail.com"
  def fromName = "Jenkins"
  def password = "#jenkins4ossci"

  Properties props = System.getProperties()
  props.put("mail.smtp.host", "smtp.gmail.com")
  props.put("mail.smtp.socketFactory.port", "465"); //SSL Port
  props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory"); //SSL Factory Class
  props.put("mail.smtp.auth", "true"); //Enabling SMTP Authentication
  props.put("mail.smtp.port", "465"); //SMTP Port

  Authenticator auth = new Authenticator() {
    protected PasswordAuthentication getPasswordAuthentication() {
      return new PasswordAuthentication(fromEmail, password)
    }
  }

  Session session = Session.getInstance(props, auth)

  MimeMessage message = new MimeMessage(session)
  message.setFrom(new InternetAddress(fromEmail, fromName))
  receivers.split(' ').each {
      message.addRecipient(Message.RecipientType.TO, new InternetAddress(it))
  }
  message.setSubject(subject)
  message.setText(text)

  manager.listener.logger.println 'Sending email to ' + receivers + '.'
  Transport.send(message)
  manager.listener.logger.println 'Email sent.'
}
'''

  // WARNING: If you edit this script, you'll have to reapprove it at
  // https://ci.pytorch.org/jenkins/scriptApproval/
  static String ciFailureEmailScript(String mailRecipients) {
    return '''
if (manager.build.result.toString().contains("FAILURE")) {
  def logLines = manager.build.logFile.readLines()
  def isFalsePositive = (logLines.count {
    it.contains("ERROR: Couldn't find any revision to build. Verify the repository and branch configuration for this job.") /* This commit is not the latest one anymore. */ \
    || it.contains("java.lang.InterruptedException") /* Job is cancelled. */ \
    || it.contains("fatal: reference is not a tree") /* Submodule commit doesn't exist, Linux */ \
    || it.contains("Server does not allow request for unadvertised object") /* Submodule commit doesn't exist, Windows */
  } > 0)
  isFalsePositive = isFalsePositive || logLines.size() == 0 /* If there is no log in the build, it means the build is cancelled by a newer commit */
  def isFalseNegative = (logLines.count {
    it.contains("clang: error: unable to execute command: Segmentation fault: 11") /* macOS clang segfault error */ \
    || it.contains("No space left on device") /* OOD error */ \
    || it.contains("virtual memory exhausted: Cannot allocate memory") /* sccache compile error */
  } > 0)
  def hasEnteredUserLand = (logLines.count {it.contains("ENTERED_USER_LAND")} > 0)
  def hasExitedUserLand = (logLines.count {it.contains("EXITED_USER_LAND")} > 0)
  def inUserLand = (hasEnteredUserLand && !hasExitedUserLand)
  if ((!inUserLand && !isFalsePositive) || isFalseNegative) {
    // manager.listener.logger.println "CI system failure occured"
    sendEmail("'''+mailRecipients+'''", 'CI system failure', 'See <'+manager.build.getEnvironment()["BUILD_URL"]+'>'+'\\n\\n'+'Log:\\n\\n'+manager.build.logFile.text)
  }
}
'''
  }
}
