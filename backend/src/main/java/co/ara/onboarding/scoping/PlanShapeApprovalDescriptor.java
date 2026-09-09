package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.workflow.PlanShapeApproval;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The one descriptor in this sub-project that does NOT delegate to a parent record
 * predicate, because its parent is a workflow version and workflow permissions are
 * catalogued ALL-only throughout. It fails closed: DEPARTMENT, TEAM and ASSIGNED all
 * match nothing, so only an ALL-scoped holder ever reads one. Returning
 * cb.conjunction() here instead would be a silent, total bypass -- the exact failure
 * the ResourceAuthorizationDescriptor contract warns about.
 */
@Component
public class PlanShapeApprovalDescriptor implements ResourceAuthorizationDescriptor<PlanShapeApproval> {
    @Override public String resourceType() { return "plan_shape_approval"; }
    @Override public Class<PlanShapeApproval> entityType() { return PlanShapeApproval.class; }
    @Override public Set<RelationshipType> assignedRelationships() { return Set.of(); }
    @Override public Specification<PlanShapeApproval> departmentScope(AuthContext c) { return (r,q,cb) -> cb.disjunction(); }
    @Override public Specification<PlanShapeApproval> teamScope(AuthContext c)       { return (r,q,cb) -> cb.disjunction(); }
    @Override public Specification<PlanShapeApproval> assignedScope(AuthContext c)   { return (r,q,cb) -> cb.disjunction(); }
}
