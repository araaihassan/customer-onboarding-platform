package co.ara.onboarding.journey;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.CustomerFactKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerDirectoryTest extends PostgresTestBase {

    @Autowired CustomerDirectory directory;
    @Autowired TenantFixture fixture;
    @Autowired RoleService roles;

    @Test
    void aVisibleCustomerResolvesToItsFacts() {
        UUID tenant = fixture.createTenant("dir-visible");
        var customerId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> customerId.set(
                fixture.createCustomer(tenant, "Acme", null, null, null)));

        fixture.runAs(tenant, () -> {
            var facts = directory.findVisible(customerId.get());
            assertThat(facts).isPresent();
            assertThat(facts.get().status()).isEqualTo("PROSPECT");
        });
    }

    /**
     * The oracle. Sub-project 1's ownership FKs answer 200 for another tenant's UUID and
     * 500 for an invented one, because PostgreSQL checks referential integrity with row
     * security bypassed -- so the two are distinguishable and the difference is an
     * existence oracle. Resolving through AuthorizedQuery collapses both to empty, and
     * journey turns empty into 404.
     */
    @Test
    void anotherTenantsCustomerIsIndistinguishableFromAnInventedOne() {
        UUID tenantA = fixture.createTenant("dir-a");
        UUID tenantB = fixture.createTenant("dir-b");
        var bCustomer = new AtomicReference<UUID>();
        fixture.runAs(tenantB, () -> bCustomer.set(
                fixture.createCustomer(tenantB, "Beta", null, null, null)));

        fixture.runAs(tenantA, () -> {
            assertThat(directory.findVisible(bCustomer.get())).isEmpty();
            assertThat(directory.findVisible(Uuid7.generate())).isEmpty();
        });
    }

    /**
     * The port applies the caller's scope, not merely their tenant. A customer the actor
     * cannot see must not become a case they can open.
     */
    @Test
    void aCustomerOutsideTheActorsScopeIsInvisible() {
        UUID tenant = fixture.createTenant("dir-scope");
        var otherOwned = new AtomicReference<UUID>();
        var actor = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID otherUser = fixture.createUser(tenant, "other@example.com");
            otherOwned.set(fixture.createCustomerOwnedBy(tenant, "Theirs", otherUser));
            actor.set(fixture.createUser(tenant, "actor@example.com"));
            grantRole(tenant, actor.get(), Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
        });

        fixture.runAsUser(tenant, actor.get(), () ->
                assertThat(directory.findVisible(otherOwned.get())).isEmpty());
    }

    /**
     * A condition key that exists in workflow but not in CustomerFacts can never be
     * true, and a publish-time validation that accepts it is worse than useless: the
     * stage it guards would be silently skipped for every case forever.
     */
    @Test
    void theCustomerFactKeysAgreeWithWhatTheFactsRecordExposes() {
        Set<String> exposed = Arrays.stream(CustomerFacts.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(n -> !n.equals("id") && !n.endsWith("Id"))
                .collect(toSet());
        assertThat(CustomerFactKeys.ALL).isEqualTo(exposed);
    }

    private void grantRole(UUID tenantId, UUID userId, Map<String, Scope> grants) {
        UUID roleId = roles.createRole("Grant-" + userId, "", grants);
        roles.assignRole(userId, roleId);
    }
}
