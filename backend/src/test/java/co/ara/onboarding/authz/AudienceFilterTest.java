package co.ara.onboarding.authz;

import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point of AudienceFilter is that it binds an actor holding the
 * permission at ALL -- which AuthorizationPredicateBuilder short-circuits past
 * before consulting any descriptor. A test that only exercises a narrow scope
 * would pass against the UNCHANGED builder and prove nothing.
 *
 * Customer is used as the subject rather than Document because this task lands
 * before the document module exists, and the mechanism must be proven before
 * anything depends on it.
 */
@Import(AudienceFilterTest.DenyEverythingAudience.class)
class AudienceFilterTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired CustomerRepository customers;
    @Autowired AuthorizedQuery authorizedQuery;

    @TestConfiguration
    static class DenyEverythingAudience {
        @Bean
        AudienceFilter<Customer> denyAllCustomers() {
            return new AudienceFilter<>() {
                @Override public Class<Customer> entityType() { return Customer.class; }
                @Override public Specification<Customer> audience(AuthContext ctx, String key) {
                    // Narrows to nothing for customer.view, and to everything for
                    // any other key -- the same permission-keyed shape
                    // DocumentAudienceFilter uses for document.manage.
                    return PermissionKeys.CUSTOMER_VIEW.equals(key)
                            ? (root, query, cb) -> cb.disjunction()
                            : (root, query, cb) -> cb.conjunction();
                }
            };
        }
    }

    @Test
    void anAllScopedHolderIsNarrowedByTheAudienceFilter() {
        UUID tenantId = fixture.createTenant("audience-all");
        var userId = new AtomicReference<UUID>();
        fixture.runAs(tenantId, () -> userId.set(fixture.createUser(tenantId, "admin@audience.test")));
        fixture.grantAtAllScope(tenantId, userId.get(), PermissionKeys.CUSTOMER_VIEW);
        fixture.runAs(tenantId, () -> fixture.createCustomer(tenantId, "Visible Co", null, null, null));

        fixture.runAsUser(tenantId, userId.get(), () -> {
            var page = authorizedQuery.findAll(customers, Customer.class,
                    PermissionKeys.CUSTOMER_VIEW, null, org.springframework.data.domain.Pageable.unpaged());
            assertThat(page.getContent())
                    .as("an ALL-scoped holder must still be bound by the audience filter")
                    .isEmpty();
        });
    }

    @Test
    void aDifferentPermissionKeyIsNotNarrowed() {
        UUID tenantId = fixture.createTenant("audience-key");
        var userId = new AtomicReference<UUID>();
        fixture.runAs(tenantId, () -> userId.set(fixture.createUser(tenantId, "admin2@audience.test")));
        fixture.grantAtAllScope(tenantId, userId.get(), PermissionKeys.CUSTOMER_EDIT);
        fixture.runAs(tenantId, () -> fixture.createCustomer(tenantId, "Editable Co", null, null, null));

        fixture.runAsUser(tenantId, userId.get(), () -> {
            var page = authorizedQuery.findAll(customers, Customer.class,
                    PermissionKeys.CUSTOMER_EDIT, null, org.springframework.data.domain.Pageable.unpaged());
            assertThat(page.getContent())
                    .as("the filter is keyed on permission; customer.edit is not narrowed")
                    .hasSize(1);
        });
    }
}
