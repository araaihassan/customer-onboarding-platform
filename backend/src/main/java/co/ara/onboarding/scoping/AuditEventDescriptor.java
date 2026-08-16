package co.ara.onboarding.scoping;

import co.ara.onboarding.audit.AuditEvent;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.identity.AppUser;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Audit events are scoped by who performed them: DEPARTMENT and TEAM resolve
 * through actorUserId joined to app_user.
 */
@Component
public class AuditEventDescriptor implements ResourceAuthorizationDescriptor<AuditEvent> {

    @Override public String resourceType() { return "audit_event"; }

    @Override public Class<AuditEvent> entityType() { return AuditEvent.class; }

    /**
     * An audit event has no owner — nobody is "assigned" a historical fact — so no
     * relationship qualifies it for ASSIGNED scope.
     */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of();
    }

    @Override public Specification<AuditEvent> departmentScope(AuthContext ctx) {
        return viaActor((root, query, cb, actor) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(actor.get("departmentId"), ctx.departmentId()));
    }

    @Override public Specification<AuditEvent> teamScope(AuthContext ctx) {
        return viaActor((root, query, cb, actor) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : actor.join("teamIds").in(ctx.teamIds()));
    }

    /**
     * AUDIT_VIEW is only granted at ALL, DEPARTMENT or TEAM, so this is unreachable
     * in practice. It still has to fail closed rather than throw or match
     * everything: a future catalog edge that widened AUDIT_VIEW to ASSIGNED would
     * otherwise turn "unreachable" into "unrestricted".
     */
    @Override public Specification<AuditEvent> assignedScope(AuthContext ctx) {
        return (root, query, cb) -> cb.disjunction();
    }

    private Specification<AuditEvent> viaActor(ActorCondition condition) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var actor = subquery.from(AppUser.class);
            subquery.select(actor.get("id"))
                    .where(condition.build(root, query, cb, actor));
            // An event with a null actor (actorType SYSTEM) matches no actor-scoped
            // predicate, which is the intended reading: system activity is visible at
            // ALL scope only.
            return root.get("actorUserId").in(subquery);
        };
    }

    @FunctionalInterface
    private interface ActorCondition {
        Predicate build(Root<AuditEvent> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<AppUser> actor);
    }
}
