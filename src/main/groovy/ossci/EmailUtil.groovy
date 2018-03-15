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
}
