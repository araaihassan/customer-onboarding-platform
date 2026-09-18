package co.ara.onboarding.document;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 22: REST-level tests proving {@link DocumentController} wires every
 * spec §8 endpoint (minus the two request/portal rows, which are Phase 5)
 * into its already-gated service method, following {@code
 * programme.ProgrammeControllerTest}'s own shape -- scope-filtering
 * correctness is already fully proven at the service layer by {@link
 * DocumentServiceTest}/{@link DocumentSharingServiceTest}/{@link
 * DocumentContentServiceTest}; these tests prove the HTTP path reaches the
 * right service method, that {@code DirectApiAccessTest}'s automatic
 * anonymous sweep needs no changes here, and the three things that
 * genuinely need a real HTTP round trip: an out-of-scope id is a 404 (never
 * a 403), a duplicate version raced through real HTTP is a 409, and the
 * download endpoint's {@code Content-Disposition} header is set correctly --
 * a controller-level guarantee {@code DocumentContentServiceTest} could only
 * ever assert the data shape for, never the literal header, because no
 * controller existed until this task (2026-09-14 plan amendment).
 *
 * A minimal real PDF prefix -- Tika's own magic-byte detection says
 * "application/pdf" -- the same fixture bytes {@link DocumentServiceTest}
 * and {@link DocumentContentServiceTest} already use.
 */
class DocumentControllerTest extends SecurityTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;

    private String tenantSlug;
    private UUID tenant;
    private AppUser actor;
    private UUID caseId;

    @BeforeEach
    void seedATenantWithAFullyGrantedActorAndACase() {
        tenantSlug = "doc-ctrl-" + Uuid7.generate();
        tenant = fixture.createTenant(tenantSlug);
        actor = fixture.createUserWithPassword(tenant,
                "actor+" + Uuid7.generate() + "@document-controller.example", "long-enough-password");

        fixture.runAs(tenant, () -> {
            UUID role = roles.createRole("Controller Actor " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL,
                    PermissionKeys.DOCUMENT_MANAGE, Scope.ALL,
                    PermissionKeys.DOCUMENT_SHARE, Scope.ALL,
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    // A real case pinned to a published template (linkAndUnlinkRoundTripThroughHttp's
                    // own cases.create, unlike journey.newCase's fixture cases) has a real
                    // currentStageId -- DocumentService.applyWriteScope resolves that Stage under
                    // WORKFLOW_VIEW before the write_scope check even runs (the same
                    // "viewing is gated by more than the record's own permission" shape
                    // DocumentServiceTest's own aTeamScopedHolderIsStillRefusedInsideAnOwnerOnlyStage
                    // documents), so this actor needs it too.
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(actor.getId(), role);

            Case c = journey.newCase(tenant);
            caseId = c.getId();
        });
    }

    @Test
    void uploadCreatesADocumentThroughMultipartFileAndMetadataParts() throws Exception {
        mvc.perform(as(uploadRequest(caseId, "Master Services Agreement.pdf"), actor))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.name").value("Master Services Agreement.pdf"))
           .andExpect(jsonPath("$.currentVersionId").isNotEmpty())
           .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void listAndForCaseAndGetAllReachTheService() throws Exception {
        UUID documentId = upload("List Doc.pdf");

        mvc.perform(as(get(base() + "/documents"), actor))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].id").exists());

        mvc.perform(as(get(base() + "/cases/" + caseId + "/documents"), actor))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].id").value(documentId.toString()));

        mvc.perform(as(get(base() + "/documents/" + documentId), actor))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(documentId.toString()));
    }

    @Test
    void patchRenamesAndRecategorisesThroughHttp() throws Exception {
        UUID documentId = upload("Original Name.pdf");

        mvc.perform(as(patch(base() + "/documents/" + documentId), actor)
                        .contentType("application/json")
                        .content("{\"name\":\"Renamed.pdf\",\"category\":\"INVOICE\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.name").value("Renamed.pdf"))
           .andExpect(jsonPath("$.category").value("INVOICE"));
    }

    @Test
    void addVersionAppendsAVersionThroughMultipart() throws Exception {
        UUID documentId = upload("Versioned.pdf");

        mvc.perform(as(multipart(base() + "/documents/" + documentId + "/versions")
                                .file(new MockMultipartFile("file", "v2.pdf", "application/pdf", PDF_BYTES)),
                        actor))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.versionNo").value(2))
           .andExpect(jsonPath("$.reviewStatus").value("PENDING"));
    }

    /**
     * Plan's own Step 1 requirement: not just the existing service-level
     * race test ({@code DocumentServiceTest.aSecondConcurrentVersionAtTheSameNumberIsA409NotASilentOverwrite}),
     * but the identical race driven through the real HTTP layer, proving
     * {@link DocumentController#addVersion} itself surfaces {@link
     * DocumentVersionConflictException} as 409 rather than an unhandled 500.
     * Retried against a fresh document (capped at 20 attempts) until a
     * genuine overlap is observed, the same reasoning {@code
     * DocumentServiceTest}'s own version race documents in full: two threads
     * racing document_version_no_uq only collide when their reads of
     * max(version_no) genuinely overlap, which is not guaranteed on every
     * attempt.
     */
    @Test
    void aSecondConcurrentVersionThroughRealHttpIsA409NotASilentOverwrite() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            boolean collided = false;
            for (int attempt = 0; attempt < 20 && !collided; attempt++) {
                UUID documentId = upload("Race Doc " + attempt + ".pdf");

                var barrier = new CyclicBarrier(2);
                Future<Integer> a = pool.submit(() -> addVersionAfterBarrier(barrier, documentId));
                Future<Integer> b = pool.submit(() -> addVersionAfterBarrier(barrier, documentId));
                int statusA = a.get(30, TimeUnit.SECONDS);
                int statusB = b.get(30, TimeUnit.SECONDS);

                long conflicts = List.of(statusA, statusB).stream().filter(s -> s == 409).count();
                if (conflicts == 0) continue; // no genuine overlap this attempt -- try again on a fresh document

                long successes = List.of(statusA, statusB).stream().filter(s -> s == 201).count();
                assertThat(conflicts).isEqualTo(1);
                assertThat(successes).isEqualTo(1);
                collided = true;
            }
            assertThat(collided)
                    .as("expected at least one of 20 concurrent HTTP attempts to genuinely race the same version_no")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    private int addVersionAfterBarrier(CyclicBarrier barrier, UUID documentId) {
        try {
            barrier.await();
            return mvc.perform(as(multipart(base() + "/documents/" + documentId + "/versions")
                                    .file(new MockMultipartFile("file", "race.pdf", "application/pdf", PDF_BYTES)),
                            actor))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Plan amendment (post-Task-16 review, 2026-09-14): the response's
     * {@code Content-Disposition} is literally {@code attachment} -- never
     * {@code inline} -- carrying the document's REAL filename, and the body
     * is the exact uploaded bytes, read back and compared, not merely
     * asserted non-empty -- proving the stream is genuinely wired end to
     * end through {@link DocumentController}'s controller, not merely that
     * the method compiles.
     */
    @Test
    void downloadEndpointStreamsExactBytesWithAnAttachmentDispositionAndTheRealFilename() throws Exception {
        String realName = "Master Services Agreement, Rev 2 (final).pdf";
        UUID documentId = upload(realName);

        MvcResult result = mvc.perform(as(get(base() + "/documents/" + documentId + "/versions/1/content"), actor))
                .andExpect(status().isOk())
                .andReturn();

        String header = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(header).as("Content-Disposition must be present").isNotNull();
        ContentDisposition parsed = ContentDisposition.parse(header);
        assertThat(parsed.getType()).as("never inline").isEqualTo("attachment");
        assertThat(parsed.getFilename()).isEqualTo(realName);

        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(PDF_BYTES);
    }

    @Test
    void retireMarksTheDocumentRetiredThroughHttp() throws Exception {
        UUID documentId = upload("To Retire.pdf");

        mvc.perform(as(post(base() + "/documents/" + documentId + "/retire"), actor)
                        .contentType("application/json")
                        .content("{\"reason\":\"Superseded by a newer contract\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    @Test
    void shareAndRevokeShareRoundTripThroughHttp() throws Exception {
        UUID documentId = upload("Shared.pdf");
        AppUser principal = fixture.createUserWithPassword(tenant,
                "principal+" + Uuid7.generate() + "@document-controller.example", "long-enough-password");

        MvcResult shareResult = mvc.perform(as(post(base() + "/documents/" + documentId + "/shares"), actor)
                        .contentType("application/json")
                        .content("{\"principalType\":\"USER\",\"principalId\":\"" + principal.getId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revokedAt").doesNotExist())
                .andReturn();

        UUID shareId = UUID.fromString(
                JsonPath.read(shareResult.getResponse().getContentAsString(), "$.id"));

        mvc.perform(as(delete(base() + "/documents/" + documentId + "/shares/" + shareId), actor))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.revokedAt").isNotEmpty());
    }

    @Test
    void linkAndUnlinkRoundTripThroughHttp() throws Exception {
        var homeCaseId = new UUID[1];
        var targetCaseId = new UUID[1];
        // Setup, not the assertion under test -- fixture create-helpers and gated
        // service calls must run inside runAs (RLS + an authenticated actor),
        // exactly as DocumentSharingServiceTest's own link tests already do.
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Link Ctrl Co " + Uuid7.generate(), null, null, null);
            UUID templateId = journey.publishedTemplate();
            homeCaseId[0] = cases.create(new CreateCaseRequest(
                    customerId, templateId, "Home Case " + Uuid7.generate(), Map.of())).id();
            targetCaseId[0] = cases.create(new CreateCaseRequest(
                    customerId, templateId, "Target Case " + Uuid7.generate(), Map.of())).id();
        });
        UUID documentId = upload(homeCaseId[0], "Linkable.pdf");

        mvc.perform(as(post(base() + "/documents/" + documentId + "/links"), actor)
                        .contentType("application/json")
                        .content("{\"caseId\":\"" + targetCaseId[0] + "\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.caseId").value(targetCaseId[0].toString()))
           .andExpect(jsonPath("$.revokedAt").doesNotExist());

        mvc.perform(as(delete(base() + "/documents/" + documentId + "/links/" + targetCaseId[0]), actor))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.revokedAt").isNotEmpty());
    }

    /**
     * The write-path/read-path invariant applied through real HTTP: an
     * out-of-scope (here, cross-tenant) id is a 404, never the 403 a bare
     * scope check alone would produce -- the same shape {@code
     * ProgrammeControllerTest.crossTenantCustomerIdAnswers404} already
     * proves for {@code programme}.
     */
    @Test
    void crossTenantDocumentIdAnswers404NeverForbidden() throws Exception {
        UUID documentId = upload("Tenant A Doc.pdf");

        String tenantBSlug = "doc-ctrl-b-" + Uuid7.generate();
        UUID tenantB = fixture.createTenant(tenantBSlug);
        AppUser actorB = fixture.createUserWithPassword(tenantB,
                "actor-b+" + Uuid7.generate() + "@document-controller.example", "long-enough-password");
        fixture.runAs(tenantB, () -> {
            UUID role = roles.createRole("Cross Tenant Actor " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
            roles.assignRole(actorB.getId(), role);
        });

        mvc.perform(as(get("/api/t/" + tenantBSlug + "/documents/" + documentId), actorB))
           .andExpect(status().isNotFound());
    }

    private UUID upload(String name) throws Exception {
        return upload(caseId, name);
    }

    private UUID upload(UUID targetCaseId, String name) throws Exception {
        MvcResult result = mvc.perform(as(uploadRequest(targetCaseId, name), actor))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(
                JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private MockHttpServletRequestBuilder uploadRequest(UUID targetCaseId, String name) {
        String metadata = "{\"name\":\"" + name + "\",\"category\":\"CONTRACT\",\"visibilityTier\":\"COMPANY_SHARED\"}";
        return multipart(base() + "/cases/" + targetCaseId + "/documents")
                .file(new MockMultipartFile("file", name, "application/pdf", PDF_BYTES))
                .file(new MockMultipartFile("metadata", "", "application/json",
                        metadata.getBytes(StandardCharsets.UTF_8)));
    }

    private String base() {
        return "/api/t/" + tenantSlug;
    }
}
