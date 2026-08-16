package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerContact;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Contacts have no ownership of their own; they inherit scope from the customer
 * they belong to. Every scope therefore resolves through a subquery over Customer
 * rather than over customer_contact's own columns.
 */
@Component
public class CustomerContactDescriptor implements ResourceAuthorizationDescriptor<CustomerContact> {

    @Override public String resourceType() { return "customer_contact"; }

    @Override public Class<CustomerContact> entityType() { return CustomerContact.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER);
    }

    @Override public Specification<CustomerContact> departmentScope(AuthContext ctx) {
        return viaCustomer((root, query, cb, customer) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(customer.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<CustomerContact> teamScope(AuthContext ctx) {
        return viaCustomer((root, query, cb, customer) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : customer.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<CustomerContact> assignedScope(AuthContext ctx) {
        return viaCustomer((root, query, cb, customer) ->
                cb.equal(customer.get("ownerUserId"), ctx.userId()));
    }

    /**
     * The subquery selects customer ids matching the condition and tests
     * customerId against them. RLS still applies to the subquery's own table, so a
     * contact cannot be reached through a customer in another tenant.
     */
    private Specification<CustomerContact> viaCustomer(CustomerCondition condition) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var customer = subquery.from(Customer.class);
            subquery.select(customer.get("id"))
                    .where(condition.build(root, query, cb, customer));
            return root.get("customerId").in(subquery);
        };
    }

    @FunctionalInterface
    private interface CustomerCondition {
        Predicate build(Root<CustomerContact> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Customer> customer);
    }
}
