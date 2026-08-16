package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.customer.Customer;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class CustomerDescriptor implements ResourceAuthorizationDescriptor<Customer> {

    @Override public String resourceType() { return "customer"; }

    @Override public Class<Customer> entityType() { return Customer.class; }

    /**
     * OWNER only in sub-project 1. CREATOR is deliberately excluded (spec 6.4):
     * having once created a record is not an ongoing relationship to it, so a
     * salesperson who creates a customer and hands it over should lose ASSIGNED
     * access rather than keep it forever.
     */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER);
    }

    /** cb.disjunction() is an empty OR, i.e. FALSE: no department means no rows. */
    @Override public Specification<Customer> departmentScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(root.get("owningDepartmentId"), ctx.departmentId());
    }

    @Override public Specification<Customer> teamScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : root.get("owningTeamId").in(ctx.teamIds());
    }

    @Override public Specification<Customer> assignedScope(AuthContext ctx) {
        return (root, query, cb) -> cb.equal(root.get("ownerUserId"), ctx.userId());
    }
}
