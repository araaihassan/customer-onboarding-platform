package co.ara.onboarding.customer;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.journey.CustomerDirectory;
import co.ara.onboarding.journey.CustomerFacts;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Component
public class JourneyCustomerDirectory implements CustomerDirectory {

    private final CustomerRepository customers;
    private final AuthorizedQuery authorizedQuery;

    public JourneyCustomerDirectory(CustomerRepository customers, AuthorizedQuery authorizedQuery) {
        this.customers = customers;
        this.authorizedQuery = authorizedQuery;
    }

    /**
     * Resolution goes through AuthorizedQuery under CUSTOMER_VIEW, so the caller's scope
     * applies and an out-of-scope or foreign-tenant id is empty rather than a row. That
     * is what makes "open a case on another tenant's customer" a 404 instead of the
     * 200-versus-500 pair an unchecked foreign key produces.
     *
     * This class is a *Directory reached with an id from a request body, which is exactly
     * the case CLAUDE.md predicted would slip past the name-shaped finder rule. Task 1
     * widened that rule to *Directory before this class existed.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerFacts> findVisible(UUID customerId) {
        try {
            Customer c = authorizedQuery.getById(
                    customers, Customer.class, PermissionKeys.CUSTOMER_VIEW, customerId);
            return Optional.of(new CustomerFacts(c.getId(), c.getStatus().name(),
                    c.getIndustry(), c.getCountry(), c.getOwnerUserId(),
                    c.getOwningDepartmentId(), c.getOwningTeamId()));
        } catch (NoSuchElementException notVisible) {
            // Empty, not a rethrow: journey maps empty to its own 404 with its own
            // message. Letting AuthorizedQuery's exception escape would make a missing
            // customer read as a missing case.
            return Optional.empty();
        }
    }
}
