package co.ara.onboarding.notification;

import co.ara.onboarding.auth.EmailMessage;
import co.ara.onboarding.auth.EmailSender;
import co.ara.onboarding.support.RecordingEmailSender;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** An EmailSender that can be told to fail or to hold sends, recording what it delivered. */
@TestConfiguration
public class FlakyEmail {

    public static final AtomicBoolean failing = new AtomicBoolean();
    public static final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    public static volatile CountDownLatch hold = null;
    /** Runs before each send is recorded, e.g. to let a send take (clock) time. */
    public static volatile Runnable onSend = null;

    public static void reset() { failing.set(false); sent.clear(); hold = null; onSend = null; }

    public static List<String> recipients() { return sent.stream().map(EmailMessage::to).toList(); }

    public static EmailMessage lastTo(String address) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(address)).reduce((a, b) -> b).orElseThrow();
    }

    @Bean static BeanPostProcessor flakySender() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof RecordingEmailSender)) return bean;
                return (EmailSender) (EmailMessage m) -> {
                    if (failing.get()) throw new IllegalStateException("smtp down");
                    var latch = hold;
                    if (latch != null) {
                        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                    var before = onSend;
                    if (before != null) before.run();
                    sent.add(m);
                };
            }
        };
    }
}
