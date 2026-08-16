package co.ara.onboarding.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Development and test sender: logs the message instead of sending it.
 *
 * The body is logged in full on purpose — activation and reset links are only
 * obtainable this way in development, and Task 25's manual verification and Task
 * 28's end-to-end tests both read the token from this output. That also means it
 * must never be the active sender in production, hence the profile.
 */
@Component
@Profile({"dev", "test"})
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.info("[email] to={} subject={}\n{}", message.to(), message.subject(), message.body());
    }
}
