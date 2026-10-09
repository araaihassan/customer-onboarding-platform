package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerOwnerAssigned;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/** NEW_CUSTOMER (plan amendment 8): a customer assigned to someone other than the actor. */
@Component
public class CustomerNotifications {

    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;

    CustomerNotifications(NotificationPipeline pipeline, SubjectFacts facts) {
        this.pipeline = pipeline;
        this.facts = facts;
    }

    @EventListener
    public void on(CustomerOwnerAssigned e) {
        if (e.ownerUserId() == null) return;
        var c = facts.customer(e.customerId());
        var draft = new NotificationPipeline.Draft(NotificationType.NEW_CUSTOMER, "customer", c.id(), null,
                "Customer assigned to you: " + Text.clip(c.displayName(), 80),
                facts.userName(e.actorId()) + " made you the owner of " + Text.clip(c.displayName(), 120) + ".",
                Links.customerLink(facts.tenantSlug(), c.id()), Tone.INFO, null);
        pipeline.deliver(draft, List.of(e.ownerUserId()), e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.CUSTOMER_VIEW, Customer.class, c.id()));
    }
}
