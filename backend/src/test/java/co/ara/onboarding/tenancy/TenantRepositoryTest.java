package co.ara.onboarding.tenancy;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class TenantRepositoryTest extends PostgresTestBase {

    @Autowired TenantRepository tenants;

    @Test
    void persistsAndLooksUpBySlug() {
        Tenant t = new Tenant();
        t.setId(Uuid7.generate());
        t.setSlug("acme");
        t.setName("Acme Corp");
        t.setStatus(TenantStatus.ACTIVE);
        tenants.save(t);

        assertThat(tenants.findBySlug("acme")).isPresent()
                .get().extracting(Tenant::getName).isEqualTo("Acme Corp");
    }
}
