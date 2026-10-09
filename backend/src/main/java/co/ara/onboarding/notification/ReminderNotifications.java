package co.ara.onboarding.notification;

import co.ara.onboarding.document.CustomerReminderQueued;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A customer reminder becomes a CUSTOMER_REMINDER outbox row (spec 6.2; plan amendment 3). */
@Component
public class ReminderNotifications {

    private final OutboxWriter outbox;

    ReminderNotifications(OutboxWriter outbox) { this.outbox = outbox; }

    @EventListener
    public void on(CustomerReminderQueued e) {
        outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.CUSTOMER_REMINDER, e.toAddress(), null, e.contactId(),
                null, e.requestId(), e.subject(), e.body(), null));
    }
}
