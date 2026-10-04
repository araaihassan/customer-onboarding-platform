package co.ara.onboarding.sla;

import co.ara.onboarding.notification.Notification;
import co.ara.onboarding.notification.NotificationRepository;
import co.ara.onboarding.notification.NotificationType;

import co.ara.onboarding.auth.EmailMessage;
import co.ara.onboarding.auth.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Spec 6.3: plain text through the existing EmailSender. Never throws; a failure is retried next run.
 * The body carries a path, not an absolute URL: there is no configured public base URL yet (6B owns one).
 */
@Component
class EscalationMailer {
    private static final Logger log = LoggerFactory.getLogger(EscalationMailer.class);
    private final EmailSender email;
    EscalationMailer(EmailSender email) { this.email = email; }

    boolean send(Notification n, String to) {
        try {
            email.send(new EmailMessage(to, n.getTitle(), n.getBody() + "\n\nOpen the case: " + n.getLinkPath()));
            return true;
        } catch (RuntimeException e) {
            log.warn("Escalation email {} to {} failed; will retry next run", n.getId(), to, e);
            return false;
        }
    }
}
