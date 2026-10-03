package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.sla.Notification;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * A notification is inherently personal (spec 7.2): below ALL it is read by its recipient only,
 * so DEPARTMENT, TEAM and ASSIGNED all collapse to {@code recipient_user_id = actor}. Fails closed
 * when there is no acting user. No HTTP read path exists in sub-project 6; 6B's inbox inherits this.
 */
@Component
public class NotificationDescriptor implements ResourceAuthorizationDescriptor<Notification> {
    @Override public String resourceType() { return "notification"; }
    @Override public Class<Notification> entityType() { return Notification.class; }
    @Override public Set<RelationshipType> assignedRelationships() { return Set.of(); }
    @Override public Specification<Notification> departmentScope(AuthContext ctx) { return recipientOnly(ctx); }
    @Override public Specification<Notification> teamScope(AuthContext ctx) { return recipientOnly(ctx); }
    @Override public Specification<Notification> assignedScope(AuthContext ctx) { return recipientOnly(ctx); }

    private Specification<Notification> recipientOnly(AuthContext ctx) {
        return (r, q, cb) -> ctx.userId() == null
                ? cb.disjunction()
                : cb.equal(r.get("recipientUserId"), ctx.userId());
    }
}
