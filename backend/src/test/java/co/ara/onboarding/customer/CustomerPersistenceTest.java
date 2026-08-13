package co.ara.onboarding.customer;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerPersistenceTest extends PostgresTestBase {

    @Autowired CustomerRepository customers;
    @Autowired CustomerContactRepository contacts;
    @Autowired TenantFixture fixture;
    @Autowired JdbcTemplate jdbc;

    /**
     * createUser is called INSIDE runAs, not before it. app_user is RLS-protected,
     * and Spring Data repository proxies manage their own transactions without
     * triggering TenantTransactionBinder, so a save outside runAs runs with no
     * tenant bound and fails the policy's WITH CHECK.
     */
    @Test
    void persistsCustomerWithOwnershipColumns() {
        UUID tenant = fixture.createTenant("cust-persist");

        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner@example.com");

            Customer c = new Customer();
            c.setId(Uuid7.generate());
            c.setTenantId(tenant);
            c.setLegalName("Acme Holdings Ltd");
            c.setDisplayName("Acme");
            c.setStatus(CustomerStatus.PROSPECT);
            c.setOwnerUserId(owner);
            c.setCreatedBy(owner);
            customers.save(c);

            assertThat(customers.findById(c.getId())).isPresent()
                .get().extracting(Customer::getOwnerUserId).isEqualTo(owner);
        });
    }

    @Test
    void contactExistsBeforeAnyUserAccount() {
        UUID tenant = fixture.createTenant("contact-before-user");

        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner2@example.com");

            Customer c = new Customer();
            c.setId(Uuid7.generate());
            c.setTenantId(tenant);
            c.setLegalName("Beta Ltd");
            c.setDisplayName("Beta");
            c.setStatus(CustomerStatus.PROSPECT);
            c.setOwnerUserId(owner);
            c.setCreatedBy(owner);
            customers.save(c);

            CustomerContact contact = new CustomerContact();
            contact.setId(Uuid7.generate());
            contact.setTenantId(tenant);
            contact.setCustomerId(c.getId());
            contact.setFullName("Jordan Reyes");
            contact.setEmail("jordan@beta.example");
            contact.setStatus(ContactStatus.ACTIVE);
            contact.setPrimaryContact(true);
            contacts.save(contact);

            // userId stays null until the invitation is accepted (spec 9.1, QA Q12).
            assertThat(contacts.findByCustomerId(c.getId()))
                    .singleElement()
                    .extracting(CustomerContact::getUserId).isNull();
        });
    }

    /**
     * Not in the plan, and it is the claim the plan makes loudest about these two
     * tables: no hard deletes is a database guarantee, not a code convention
     * (spec 9.4). Nothing asserted it. V8 grants only SELECT, INSERT and UPDATE,
     * and V5_1 revoked the schema-wide default, so onboarding_app should have no
     * DELETE on either table.
     *
     * Needs no tenant bound: PostgreSQL checks table privileges before row
     * security, so this fails on privilege regardless of RLS. That also means it
     * proves the grant, not the policy.
     *
     * hasStackTraceContaining, matching AppRoleTest: PostgreSQL reports
     * insufficient privilege as SQLState 42501, and Spring's
     * SQLStateSQLExceptionTranslator maps the whole 42xxx class to
     * BadSqlGrammarException, whose own message says only "bad SQL grammar".
     * "permission denied" lives in the wrapped cause, so asserting on the
     * top-level message alone fails while the privilege check is working
     * perfectly.
     */
    @Test
    void applicationRoleCannotDeleteBusinessRecords() {
        assertThatThrownBy(() -> jdbc.execute("DELETE FROM customer"))
                .hasStackTraceContaining("permission denied");

        assertThatThrownBy(() -> jdbc.execute("DELETE FROM customer_contact"))
                .hasStackTraceContaining("permission denied");
    }
}
