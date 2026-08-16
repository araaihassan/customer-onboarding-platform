package co.ara.onboarding.support;

import co.ara.onboarding.auth.EmailMessage;
import co.ara.onboarding.auth.EmailSender;
import co.ara.onboarding.auth.LoggingEmailSender;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures what was emailed, so a test can redeem a token exactly as a recipient
 * would.
 *
 * Activation and reset tokens exist only in memory and in the message body — the
 * row holds a hash — so a test that drives a flow end to end has no other way to
 * obtain one. Reading them back out of the log with OutputCaptureExtension was the
 * alternative and was rejected: it binds the assertion to a log level and a
 * formatting pattern, either of which can change without the flow changing.
 *
 * It delegates to {@link LoggingEmailSender} rather than replacing it, so the
 * message is still logged and the dev-log mechanism Task 28 reads tokens from stays
 * exercised. @Primary rather than a @TestConfiguration: a nested test
 * configuration forks the Spring context cache key and pays a second full
 * application start-up, and every test in the suite is happy to have this sender.
 */
@Component
@Primary
public class RecordingEmailSender implements EmailSender {

    private final LoggingEmailSender log = new LoggingEmailSender();
    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        log.send(message);
        sent.add(message);
    }

    /** The most recent message to an address; tests use distinct addresses rather than clearing. */
    public Optional<EmailMessage> lastTo(String address) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(address))
                .reduce((first, second) -> second);
    }

    /**
     * The bare token from the last message to an address.
     *
     * Every sender in this system ends its body with "…: &lt;token&gt;" and nothing
     * after it, so the last whitespace-delimited word is the token. See
     * CLAUDE.md — the body carries a bare token, not a URL.
     */
    public Optional<String> tokenTo(String address) {
        return lastTo(address)
                .map(m -> m.body().trim())
                .map(body -> body.substring(body.lastIndexOf(' ') + 1));
    }
}
