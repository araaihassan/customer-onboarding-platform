package co.ara.onboarding.programme;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sub-project 3A, Task 27.6 (inserted plan amendment -- see
 * {@code .superpowers/sdd/2026-09-08-programmes-and-customer-plans/task-27.6-brief.md}):
 * REST-level smoke tests proving {@link ProgrammeController#listForCustomer}
 * wires {@code GET /customers/{customerId}/programmes} into {@link
 * ProgrammeService#listForCustomer} correctly, following {@code
 * journey.PlanRevisionControllerTest}'s own shape.
 *
 * Scope-filtering correctness (the DEPARTMENT-narrowed read, and the
 * {@code customerId}-through-AuthorizedQuery escalation guard) is already
 * fully proven at the service layer by {@code ProgrammeServiceTest}. These
 * tests deliberately do NOT re-derive that: they only prove the HTTP path
 * reaches the right service method (one happy-path assertion), the same
 * {@code programme.view} gate the service carries is enforced end to end
 * (one 403 smoke test), and a cross-tenant customer id is a 404, never a 403
 * or a 500 (CLAUDE.md's own invariant).
 */
class ProgrammeControllerTest extends SecurityTestBase {

    @Autowired ProgrammeService programmeService;

    private UUID tenant;
    private String tenantSlug;
    private AppUser reader;
    private AppUser noGrantActor;
    private UUID customerId;
    private UUID programmeId;

    @BeforeEach
    void seedACustomerWithOneProgrammeAndTwoActors() {
        tenantSlug = "programme-ctrl-" + Uuid7.generate();
        tenant = fixture.createTenant(tenantSlug);

        reader = fixture.createUserWithPassword(tenant,
                "reader+" + Uuid7.generate() + "@programme-controller.example", "long-enough-password");
        noGrantActor = fixture.createUserWithPassword(tenant,
                "no-grant+" + Uuid7.generate() + "@programme-controller.example", "long-enough-password");

        fixture.runAs(tenant, () -> {
            UUID role = roles.createRole("Controller Reader " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.PROGRAMME_VIEW, Scope.ALL,
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            roles.assignRole(reader.getId(), role);

            customerId = fixture.createCustomer(tenant, "Controller Co " + Uuid7.generate(), null, null, null);
            programmeId = programmeService.create(new CreateProgrammeRequest(
                    "Controller Programme " + Uuid7.generate(), customerId, null, null, null, null)).id();
        });
    }

    @Test
    void listForCustomerReachesTheServiceAndReturnsProgrammesForThatCustomer() throws Exception {
        mvc.perform(as(get(base()), reader))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(1))
           .andExpect(jsonPath("$[0].id").value(programmeId.toString()))
           .andExpect(jsonPath("$[0].customerId").value(customerId.toString()));
    }

    @Test
    void listForCustomerAnswers403ForAnActorLackingProgrammeView() throws Exception {
        mvc.perform(as(get(base()), noGrantActor))
           .andExpect(status().isForbidden());
    }

    /**
     * A cross-tenant id is consistently a 404 (CLAUDE.md's own invariant) --
     * never the 403 a scope check alone would produce, nor a 500. {@code
     * inTenantB} holds the exact same two permissions {@code reader} does, at
     * ALL, inside a completely different tenant, so any non-404 result here
     * would be a real cross-tenant leak, not a permission gap.
     */
    @Test
    void crossTenantCustomerIdAnswers404() throws Exception {
        String tenantBSlug = "programme-ctrl-b-" + Uuid7.generate();
        UUID tenantB = fixture.createTenant(tenantBSlug);
        AppUser inTenantB = fixture.createUserWithPassword(tenantB,
                "actor+" + Uuid7.generate() + "@programme-controller.example", "long-enough-password");
        fixture.runAs(tenantB, () -> {
            UUID role = roles.createRole("Cross Tenant Actor " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.PROGRAMME_VIEW, Scope.ALL,
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            roles.assignRole(inTenantB.getId(), role);
        });

        mvc.perform(as(get("/api/t/" + tenantBSlug + "/customers/" + customerId + "/programmes"), inTenantB))
           .andExpect(status().isNotFound());
    }

    private String base() {
        return "/api/t/" + tenantSlug + "/customers/" + customerId + "/programmes";
    }
}
