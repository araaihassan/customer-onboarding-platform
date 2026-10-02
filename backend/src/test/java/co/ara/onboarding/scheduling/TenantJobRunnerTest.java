package co.ara.onboarding.scheduling;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

class TenantJobRunnerTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired TenantJobRunner runner;
    @Autowired AuthContextProvider contexts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void runsOncePerActiveTenantBoundAndAsTheSystemActor() {
        UUID a = fixture.createTenant("job-a");
        UUID b = fixture.createTenant("job-b");
        ownerJdbc().update("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?", b);
        Map<UUID, String> seen = new ConcurrentHashMap<>();
        runner.forEachTenant("test-job", tenant -> {
            assertThat(TenantContext.getRequired()).isEqualTo(tenant);
            assertThat(contexts.current().userType()).isEqualTo(UserType.SYSTEM);
            // RLS is bound: the GUC equals the tenant.
            seen.put(tenant, jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class));
        });
        assertThat(seen).containsEntry(a, a.toString()).doesNotContainKey(b);
    }

    @Test
    void oneTenantsFailureDoesNotStopTheOthers() {
        UUID a = fixture.createTenant("job-fail-a");
        UUID b = fixture.createTenant("job-fail-b");
        Set<UUID> ran = ConcurrentHashMap.newKeySet();
        runner.forEachTenant("test-fail", tenant -> {
            ran.add(tenant);
            if (tenant.equals(a)) throw new IllegalStateException("boom");
        });
        assertThat(ran).contains(a, b);
    }

    @Test
    void aSecondRunnerForTheSameTenantAndJobSkips() throws Exception {
        UUID tenant = fixture.createTenant("job-lock");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> first = pool.submit(() -> runner.forTenant("locked-job", tenant, t -> {
            inside.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> second = pool.submit(() -> runner.forTenant("locked-job", tenant, t -> {}));
        assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
    }

    @Test
    void theRequestScopeAndPrincipalDoNotLeakOutOfTheRun() {
        UUID tenant = fixture.createTenant("job-leak");
        // The Spring test harness installs its own request scope for the whole test method, so
        // "no leak" means the pre-existing one is back, not that none exists.
        var before = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        runner.forTenant("leak-job", tenant, t -> {});
        assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isSameAs(before);
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    void aCallersOwnRequestScopeAndPrincipalAreRestored() {
        UUID tenant = fixture.createTenant("job-restore");
        var callerAttributes = new org.springframework.web.context.request.ServletRequestAttributes(
                new org.springframework.mock.web.MockHttpServletRequest());
        var callerAuth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new co.ara.onboarding.authz.AuthenticatedPrincipal(tenant, UUID.randomUUID()), null, java.util.List.of());
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(callerAttributes);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(callerAuth);
        try {
            runner.forTenant("restore-job", tenant, t -> {});
            assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isSameAs(callerAttributes);
            assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isSameAs(callerAuth);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }
}
