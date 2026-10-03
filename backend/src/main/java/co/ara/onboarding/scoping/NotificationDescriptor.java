package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.sla.Notification;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Notifications are read only at ALL (the system actor's sla.view) in sub-project 6; 6B adds the
 * recipient-scoped read. Every narrower scope fails closed.
 */
@Component
public class NotificationDescriptor implements ResourceAuthorizationDescriptor<Notification> {
    @Override public String resourceType() { return "notification"; }
    @Override public Class<Notification> entityType() { return Notification.class; }
    @Override public Set<RelationshipType> assignedRelationships() { return Set.of(); }
    @Override public Specification<Notification> departmentScope(AuthContext ctx) { return (r, q, cb) -> cb.disjunction(); }
    @Override public Specification<Notification> teamScope(AuthContext ctx) { return (r, q, cb) -> cb.disjunction(); }
    @Override public Specification<Notification> assignedScope(AuthContext ctx) { return (r, q, cb) -> cb.disjunction(); }
}
