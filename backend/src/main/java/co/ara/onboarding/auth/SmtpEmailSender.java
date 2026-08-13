package co.ara.onboarding.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Profile("!dev & !test")
@Component
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;

    public SmtpEmailSender(JavaMailSender mail) { this.mail = mail; }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage simple = new SimpleMailMessage();
        simple.setTo(message.to());
        simple.setSubject(message.subject());
        simple.setText(message.body());
        mail.send(simple);
    }
}
