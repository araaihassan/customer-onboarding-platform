package co.ara.onboarding.authz;

import org.springframework.data.jpa.domain.Specification;

import java.util.Set;

/**
 * Declares, for one resource type, how DEPARTMENT, TEAM and ASSIGNED resolve into
 * query predicates.
 *
 * Every implementation must fail closed: when the AuthContext carries nothing to
 * match on — no department, no teams — the returned Specification must match
 * nothing (cb.disjunction()), never everything. A scope that widens when its
 * input is missing is indistinguishable from ALL.
 *
 * Implementations live in the `scoping` module rather than in the module owning
 * the entity. They depend on authz for this interface and on the entity's module
 * for the entity, and authz already depends on audit while customer and identity
 * depend on authz — so a descriptor placed in the owning module closes a cycle.
 * Nothing imports the implementations; Spring collects them by this interface.
 */
public interface ResourceAuthorizationDescriptor<T> {

    /** Matches Permission.resourceType(), which is how the registry validates coverage. */
    String resourceType();

    Class<T> entityType();

    /** Which personal relationships qualify a record for ASSIGNED scope. */
    Set<RelationshipType> assignedRelationships();

    Specification<T> departmentScope(AuthContext ctx);

    Specification<T> teamScope(AuthContext ctx);

    Specification<T> assignedScope(AuthContext ctx);
}
