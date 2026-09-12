package co.ara.onboarding.customer;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.workflow.CustomerDirectory;
import co.ara.onboarding.workflow.CustomerSummary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * customer's implementation of the {@code workflow.CustomerDirectory} inversion
 * (sub-project 3A, Task 16) -- the same shape {@link JourneyCustomerDirectory}
 * already established for {@code journey}'s own port, adopted here because a
 * direct {@code workflow -> customer} dependency closes a module cycle through
 * {@code journey}. See {@code workflow.CustomerDirectory}'s own javadoc for the
 * cycle this class exists to avoid.
 *
 * Resolution goes through {@link AuthorizedQuery} under {@code CUSTOMER_VIEW},
 * so the caller's scope applies and an out-of-scope or foreign-tenant id is
 * empty rather than a row -- what makes cloning a template for another
 * tenant's customer a 404 instead of the 200-versus-500 pair an unchecked
 * foreign key produces.
 */
@Component
public class WorkflowCustomerDirectory implements CustomerDirectory {

    private final CustomerRepository customers;
    private final AuthorizedQuery authorizedQuery;

    public WorkflowCustomerDirectory(CustomerRepository customers, AuthorizedQuery authorizedQuery) {
        this.customers = customers;
        this.authorizedQuery = authorizedQuery;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> findVisible(UUID customerId) {
        try {
            Customer c = authorizedQuery.getById(
                    customers, Customer.class, PermissionKeys.CUSTOMER_VIEW, customerId);
            return Optional.of(new CustomerSummary(c.getId(), c.getDisplayName()));
        } catch (NoSuchElementException notVisible) {
            // Empty, not a rethrow: CustomerTemplateService maps empty to its own
            // NoSuchElementException/404 -- see JourneyCustomerDirectory's own
            // comment for the identical reasoning.
            return Optional.empty();
        }
    }
}
