package co.ara.onboarding.document;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 23's own plan-gap closure: {@code DocumentRequestController} is named
 * in the plan's File Structure overview but built by no task at all (see this
 * task's own brief) -- without it, neither spec Section 8 row for
 * {@code document.request} would ever be reachable over HTTP. Follows {@link
 * DocumentControllerTest}'s own established shape: REST-level tests proving
 * the controller wires both endpoints into their already-gated,
 * already-scope-proven service methods ({@link DocumentRequestServiceTest}
 * carries the scope/write-path/never-satisfies proofs), plus the one thing
 * that genuinely needs a real HTTP round trip -- an out-of-scope id is a 404,
 * never a 403.
 */
class DocumentRequestControllerTest extends SecurityTestBase {

    @Autowired JourneyFixtures journey;

    private String tenantSlug;
    private UUID tenant;
    private AppUser actor;
    private UUID caseId;

    @BeforeEach
    void seedATenantWithAFullyGrantedActorAndACase() {
        tenantSlug = "doc-req-ctrl-" + Uuid7.generate();
        tenant = fixture.createTenant(tenantSlug);
        actor = fixture.createUserWithPassword(tenant,
                "actor+" + Uuid7.generate() + "@document-request-controller.example", "long-enough-password");

        fixture.runAs(tenant, () -> {
            UUID role = roles.createRole("Request Controller Actor " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL));
            roles.assignRole(actor.getId(), role);
            caseId = journey.newCase(tenant).getId();
        });
    }

    @Test
    void createAndWithdrawRoundTripThroughHttp() throws Exception {
        MvcResult createResult = mvc.perform(as(post(base() + "/cases/" + caseId + "/document-requests"), actor)
                        .contentType("application/json")
                        .content("{\"category\":\"CONTRACT\",\"description\":\"Please supply the signed MSA\","
                                + "\"requiresReview\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("CONTRACT"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.requirementId").doesNotExist())
                .andReturn();

        UUID requestId = UUID.fromString(
                JsonPath.read(createResult.getResponse().getContentAsString(), "$.id"));

        mvc.perform(as(post(base() + "/document-requests/" + requestId + "/withdraw"), actor)
                        .contentType("application/json")
                        .content("{\"reason\":\"Customer already sent it by email\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    void withdrawRejectsABlankReasonThroughHttp() throws Exception {
        MvcResult createResult = mvc.perform(as(post(base() + "/cases/" + caseId + "/document-requests"), actor)
                        .contentType("application/json")
                        .content("{\"category\":\"OTHER\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID requestId = UUID.fromString(
                JsonPath.read(createResult.getResponse().getContentAsString(), "$.id"));

        mvc.perform(as(post(base() + "/document-requests/" + requestId + "/withdraw"), actor)
                        .contentType("application/json")
                        .content("{\"reason\":\"\"}"))
           .andExpect(status().isBadRequest());
    }

    /**
     * The write-path/read-path invariant applied through real HTTP: an
     * out-of-scope (here, cross-tenant) id is a 404, never the 403 a bare
     * scope check alone would produce -- the same shape {@code
     * DocumentControllerTest.crossTenantDocumentIdAnswers404NeverForbidden}
     * already proves for {@code document.share}.
     */
    @Test
    void crossTenantCaseIdOnCreateAnswers404NeverForbidden() throws Exception {
        String tenantBSlug = "doc-req-ctrl-b-" + Uuid7.generate();
        UUID tenantB = fixture.createTenant(tenantBSlug);
        AppUser actorB = fixture.createUserWithPassword(tenantB,
                "actor-b+" + Uuid7.generate() + "@document-request-controller.example", "long-enough-password");
        fixture.runAs(tenantB, () -> {
            UUID role = roles.createRole("Cross Tenant Actor " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL));
            roles.assignRole(actorB.getId(), role);
        });

        mvc.perform(as(post("/api/t/" + tenantBSlug + "/cases/" + caseId + "/document-requests"), actorB)
                        .contentType("application/json")
                        .content("{\"category\":\"OTHER\"}"))
           .andExpect(status().isNotFound());
    }

    private String base() {
        return "/api/t/" + tenantSlug;
    }
}
