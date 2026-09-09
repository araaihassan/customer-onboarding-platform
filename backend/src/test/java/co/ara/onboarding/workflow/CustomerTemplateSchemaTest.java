package co.ara.onboarding.workflow;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 15: schema-level proof for workflow_template's two new nullable columns
 * (QA Q21) -- customer_id and cloned_from_template_id. The whole point is the
 * partial unique index workflow_template_customer_clone_uq: one clone per
 * customer per catalogue template, while the catalogue itself (every row today,
 * customer_id IS NULL) stays completely unconstrained by it.
 */
class CustomerTemplateSchemaTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc; // the onboarding_app connection
    @Autowired TenantFixture fixture;

    private UUID tenant;
    private UUID acme;
    private UUID globex;
    private UUID standard;

    @BeforeEach
    void seedTenantCustomersAndCatalogueTemplate() {
        tenant = fixture.createTenant("tmpl-schema-" + Uuid7.generate());
        var acmeRef = new AtomicReference<UUID>();
        var globexRef = new AtomicReference<UUID>();
        var standardRef = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            acmeRef.set(fixture.createCustomer(tenant, "Acme", null, null, null));
            globexRef.set(fixture.createCustomer(tenant, "Globex", null, null, null));
            standardRef.set(insertTemplate(Uuid7.generate(), null, null, "Standard Onboarding"));
        });

        acme = acmeRef.get();
        globex = globexRef.get();
        standard = standardRef.get();
    }

    @Test
    void oneCustomerHoldsAtMostOneCloneOfAGivenCatalogueTemplate() {
        fixture.runAs(tenant, () -> insertTemplate(Uuid7.generate(), acme, standard, "Acme's Onboarding"));

        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                insertTemplate(Uuid7.generate(), acme, standard, "Acme's Onboarding (dup)")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoCustomersMayEachCloneTheSameCatalogueTemplate() {
        fixture.runAs(tenant, () -> insertTemplate(Uuid7.generate(), acme, standard, "Acme's Onboarding"));

        assertThatNoException().isThrownBy(() -> fixture.runAs(tenant, () ->
                insertTemplate(Uuid7.generate(), globex, standard, "Globex's Onboarding")));
    }

    /**
     * Every template today has customer_id NULL. A plain UNIQUE would collapse the
     * entire catalogue into one row -- which is why the index is partial.
     */
    @Test
    void theCatalogueIsUnconstrained() {
        fixture.runAs(tenant, () -> insertTemplate(Uuid7.generate(), null, null, "Catalogue Template One"));

        assertThatNoException().isThrownBy(() -> fixture.runAs(tenant, () ->
                insertTemplate(Uuid7.generate(), null, null, "Catalogue Template Two")));
    }

    // ---- helpers ------------------------------------------------------------

    /** Must run inside {@link TenantFixture#runAs} -- workflow_template is RLS-protected. */
    private UUID insertTemplate(UUID id, UUID customerId, UUID clonedFromTemplateId, String name) {
        jdbc.update("""
                INSERT INTO workflow_template
                    (id, tenant_id, customer_id, cloned_from_template_id, name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', now(), now())
                """, id, tenant, customerId, clonedFromTemplateId, name);
        return id;
    }
}
