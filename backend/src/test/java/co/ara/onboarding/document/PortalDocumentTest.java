package co.ara.onboarding.document;

import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 26: the real portal upload endpoint -- {@code GET /portal/documents}
 * (needs no new test of its own; it is the identical
 * {@link DocumentService#list} path {@link DocumentServiceTest} and {@code
 * scoping.DocumentAudienceFilter}'s own tests already cover, exercised here
 * only incidentally as the read-back half of each write test below) and
 * {@code POST /portal/cases/{caseId}/documents}, the first portal (external
 * customer) WRITE path this codebase has ever built.
 *
 * <p>The size-ceiling (413) proof lives in a SEPARATE class,
 * {@link PortalMultipartUploadSizeTest} -- {@code MultipartUploadSizeTest}'s
 * own javadoc already explains why MockMvc's {@code multipart(...)} request
 * is structurally blind to the servlet container's real multipart parsing
 * ({@code MockMultipartHttpServletRequest} already implements {@code
 * MultipartHttpServletRequest}, so {@code DispatcherServlet#checkMultipart}
 * never invokes the real resolver this ceiling depends on) -- so that one
 * proof needs the identical {@code webEnvironment = RANDOM_PORT} +
 * {@code TestRestTemplate} shape, which does not mix cleanly into this
 * MockMvc-based class.
 */
class PortalDocumentTest extends SecurityTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] HTML_BYTES =
            ("<!DOCTYPE html>\n<html><head><title>Not a PDF</title></head>"
                    + "<body>Not actually a PDF</body></html>").getBytes(StandardCharsets.UTF_8);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired AppUserRepository appUsers;
    @Autowired CustomerContactRepository customerContacts;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentVersionRepository versionRepository;

    private String tenantSlug;
    private UUID tenant;

    private void seedTenant() {
        tenantSlug = "portal-doc-" + Uuid7.generate();
        tenant = fixture.createTenant(tenantSlug);
    }

    /**
     * SCREENS.md §17's first choice, "Everyone at [Customer] on this
     * onboarding" -> {@link VisibilityTier#COMPANY_SHARED}: visible to any
     * OTHER active contact at the same customer, not just the uploader.
     */
    @Test
    void uploadWithCompanySharedTierIsVisibleToASecondActiveContactAtTheSameCustomer() throws Exception {
        seedTenant();
        var customerId = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Company Shared Co " + Uuid7.generate(), null, null, null);
            caseId[0] = journey.newCaseForCustomer(tenant, customerId[0]).getId();
        });
        AppUser contactA = portalContact(customerId[0], "contact-a");
        AppUser contactB = portalContact(customerId[0], "contact-b");

        UUID documentId = upload(caseId[0], "Company.pdf", "OTHER", "COMPANY_SHARED", PDF_BYTES, "application/pdf", contactA);

        mvc.perform(as(get(base() + "/documents"), contactB))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].id").value(documentId.toString()));
    }

    /**
     * SCREENS.md §17's second choice, "Only me and the review team" ->
     * {@link VisibilityTier#CONTACT_ONLY}: visible to the uploading contact,
     * NOT to another active contact at the same customer.
     */
    @Test
    void uploadWithContactOnlyTierIsVisibleOnlyToTheUploadingContact() throws Exception {
        seedTenant();
        var customerId = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Contact Only Co " + Uuid7.generate(), null, null, null);
            caseId[0] = journey.newCaseForCustomer(tenant, customerId[0]).getId();
        });
        AppUser contactA = portalContact(customerId[0], "contact-a");
        AppUser contactB = portalContact(customerId[0], "contact-b");

        UUID documentId = upload(caseId[0], "Mine.pdf", "OTHER", "CONTACT_ONLY", PDF_BYTES, "application/pdf", contactA);

        mvc.perform(as(get(base() + "/documents"), contactA))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].id").value(documentId.toString()));

        mvc.perform(as(get(base() + "/documents"), contactB))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content").isEmpty());
    }

    /**
     * SCREENS.md §17's third choice, "Selected contacts only" ->
     * {@link VisibilityTier#SENSITIVE}. {@code
     * scoping.DocumentAudienceFilter#portalAudience}'s own javadoc: "SENSITIVE
     * appears in neither disjunct... only ever through the explicit share" --
     * proven here to reach nobody by tier alone, not even the uploading
     * contact, exactly {@link PortalCreateDocumentRequest}'s own javadoc
     * documents as a real, deliberate product gap this sub-project does not
     * close (no portal-side sharing mechanism exists yet).
     */
    @Test
    void uploadWithSensitiveTierReachesNobodyByTierAloneNotEvenTheUploader() throws Exception {
        seedTenant();
        var customerId = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Sensitive Co " + Uuid7.generate(), null, null, null);
            caseId[0] = journey.newCaseForCustomer(tenant, customerId[0]).getId();
        });
        AppUser contactA = portalContact(customerId[0], "contact-a");

        upload(caseId[0], "Sensitive.pdf", "OTHER", "SENSITIVE", PDF_BYTES, "application/pdf", contactA);

        mvc.perform(as(get(base() + "/documents"), contactA))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content").isEmpty());
    }

    /**
     * The vulnerability the whole task exists to close, proven from the
     * OUTSIDE (through real HTTP): a portal contact of customer A must not be
     * able to upload against customer B's case, even though both customers
     * live in the SAME tenant (so RLS alone would not stop it).
     */
    @Test
    void uploadRefusesAgainstAnotherCustomersCase() throws Exception {
        seedTenant();
        var customerAId = new UUID[1];
        var customerBCaseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerAId[0] = fixture.createCustomer(tenant, "Customer A " + Uuid7.generate(), null, null, null);
            UUID customerBId = fixture.createCustomer(tenant, "Customer B " + Uuid7.generate(), null, null, null);
            customerBCaseId[0] = journey.newCaseForCustomer(tenant, customerBId).getId();
        });
        AppUser contactA = portalContact(customerAId[0], "contact-a");

        mvc.perform(as(uploadRequest(customerBCaseId[0], "Sneaky.pdf", "OTHER", "COMPANY_SHARED",
                        PDF_BYTES, "application/pdf"), contactA))
           .andExpect(status().isNotFound());

        fixture.runAs(tenant, () ->
                assertThat(documentRepository.findByCaseId(customerBCaseId[0])).isEmpty());
    }

    /** {@code PortalContactDirectory.findActiveContactForUser}'s existing empty-result path, wired up here. */
    @Test
    void uploadRefusesARetiredContact() throws Exception {
        seedTenant();
        var customerId = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Retired Contact Co " + Uuid7.generate(), null, null, null);
            caseId[0] = journey.newCaseForCustomer(tenant, customerId[0]).getId();
        });
        AppUser contact = portalContact(customerId[0], "retiring-contact");
        fixture.retireContactFor(tenant, contact.getId());

        mvc.perform(as(uploadRequest(caseId[0], "Too Late.pdf", "OTHER", "COMPANY_SHARED",
                        PDF_BYTES, "application/pdf"), contact))
           .andExpect(status().isNotFound());
    }

    /**
     * {@code owner_contact_id} is always the RESOLVED acting contact --
     * {@link PortalCreateDocumentRequest} carries no field for a caller to
     * even attempt to set it, and an extra, unexpected JSON property is
     * silently ignored (Spring Boot's default Jackson configuration), never
     * causing a different value to be written.
     */
    @Test
    void uploadForcesOwnerContactIdToTheActingContactEvenWithAnExtraUnexpectedJsonField() throws Exception {
        seedTenant();
        var customerId = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Owner Force Co " + Uuid7.generate(), null, null, null);
            caseId[0] = journey.newCaseForCustomer(tenant, customerId[0]).getId();
        });
        AppUser contact = portalContact(customerId[0], "owner-contact");

        String metadata = JSON.writeValueAsString(Map.of(
                "name", "Owner Test.pdf", "category", "OTHER", "visibilityTier", "CONTACT_ONLY",
                // Not a real field on PortalCreateDocumentRequest -- must be silently ignored,
                // never smuggled through to override the acting contact.
                "ownerContactId", UUID.randomUUID().toString()));
        MockHttpServletRequestBuilder request = multipart(base() + "/cases/" + caseId[0] + "/documents")
                .file(new MockMultipartFile("file", "Owner Test.pdf", "application/pdf", PDF_BYTES))
                .file(new MockMultipartFile("metadata", "", "application/json",
                        metadata.getBytes(StandardCharsets.UTF_8)));

        MvcResult result = mvc.perform(as(request, contact))
                .andExpect(status().isCreated())
                .andReturn();
        UUID documentId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));

        fixture.runAs(tenant, () -> {
            CustomerContact actingContact = customerContacts.findByUserId(contact.getId()).orElseThrow();
            Document d = documentRepository.findById(documentId).orElseThrow();
            // The acting contact's own customer_contact id -- never the random
            // one the request body tried to smuggle in.
            assertThat(d.getOwnerContactId()).isEqualTo(actingContact.getId());
        });
    }

    /**
     * Task 7's ruling, the sniffed-content half, proven inherited rather than
     * forked: real HTML bytes declared as CONTRACT (PDF/DOC/DOCX only) is
     * refused because the bytes themselves sniff as text/html -- the same
     * mechanism {@code DocumentServiceTest
     * .anUploadWhoseSniffedContentDoesNotMatchItsDeclaredCategoryIsRefused}
     * proves for the internal path, exercised here at the portal endpoint.
     */
    @Test
    void uploadWhoseSniffedContentMismatchesItsDeclaredCategoryIsRefused() throws Exception {
        seedTenant();
        var customerId = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Sniff Co " + Uuid7.generate(), null, null, null);
            caseId[0] = journey.newCaseForCustomer(tenant, customerId[0]).getId();
        });
        AppUser contact = portalContact(customerId[0], "sniff-contact");

        mvc.perform(as(uploadRequest(caseId[0], "contract.pdf", "CONTRACT", "COMPANY_SHARED",
                        HTML_BYTES, "application/pdf"), contact))
           .andExpect(status().isUnprocessableEntity());

        fixture.runAs(tenant, () -> assertThat(documentRepository.findByCaseId(caseId[0])).isEmpty());
    }

    /**
     * Task 7's ruling, the SHA-256 half: the digest stored on {@code
     * document_version} is the actual digest of the uploaded bytes, computed
     * through the identical {@link DocumentService} machinery {@code
     * DocumentServiceTest.aSuccessfulUploadsSha256IsTheActualDigestOfTheUploadedBytes}
     * already proves for the internal path.
     */
    @Test
    void uploadsSha256IsComputedAndStoredTheSameWayAsTheInternalPath() throws Exception {
        seedTenant();
        var customerId = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Sha256 Co " + Uuid7.generate(), null, null, null);
            caseId[0] = journey.newCaseForCustomer(tenant, customerId[0]).getId();
        });
        AppUser contact = portalContact(customerId[0], "sha-contact");

        UUID documentId = upload(caseId[0], "Cert.pdf", "OTHER", "COMPANY_SHARED", PDF_BYTES, "application/pdf", contact);

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF_BYTES));
        fixture.runAs(tenant, () -> {
            DocumentVersion v = versionRepository.findByDocumentId(documentId).get(0);
            assertThat(v.getSha256()).isEqualTo(expected);
        });
    }

    /**
     * The deliberate ruling proven positively, not just documented:
     * {@link DocumentService#uploadFromPortal} never calls {@code
     * applyWriteScope} at all, so a portal upload succeeds even inside an
     * {@code OWNER_ONLY} stage the uploading contact has no relationship to
     * whatsoever -- the mirror image of {@code DocumentServiceTest
     * .aTeamScopedHolderIsStillRefusedInsideAnOwnerOnlyStage}, which proves
     * the INTERNAL path IS refused in the identical stage shape.
     */
    @Test
    void uploadSucceedsInsideAnOwnerOnlyStageProvingWriteScopeIsNeverApplied() throws Exception {
        seedTenant();
        var customerId = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID caseOwner = fixture.createUser(tenant, "case-owner+" + Uuid7.generate() + "@example.com");

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            customerId[0] = fixture.createCustomer(tenant, "Owner Only Portal Co " + Uuid7.generate(),
                    caseOwner, null, null);
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId[0], journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
        });
        AppUser contact = portalContact(customerId[0], "owner-only-contact");

        // Never granted any relationship to caseOwner or the stage at all --
        // if write_scope were consulted here, this would 403/404. It must not.
        mvc.perform(as(uploadRequest(caseId[0], "Requested.pdf", "OTHER", "COMPANY_SHARED",
                        PDF_BYTES, "application/pdf"), contact))
           .andExpect(status().isCreated());
    }

    /** A cross-tenant caseId is a 404, RLS aside -- the same shape every other read/write in this codebase proves. */
    @Test
    void uploadWithACrossTenantCaseIdIsA404() throws Exception {
        seedTenant();
        UUID tenantB = fixture.createTenant("portal-doc-b-" + Uuid7.generate());
        var customerId = new UUID[1];
        var caseInTenantB = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Cross Tenant Co " + Uuid7.generate(), null, null, null);
        });
        fixture.runAs(tenantB, () -> caseInTenantB[0] = journey.newCase(tenantB).getId());
        AppUser contact = portalContact(customerId[0], "cross-tenant-contact");

        mvc.perform(as(uploadRequest(caseInTenantB[0], "Nope.pdf", "OTHER", "COMPANY_SHARED",
                        PDF_BYTES, "application/pdf"), contact))
           .andExpect(status().isNotFound());
    }

    /**
     * Spec 6.8: a nonexistent caseId must be INDISTINGUISHABLE from one that
     * exists but belongs to a different customer -- both are the identical
     * {@link java.util.NoSuchElementException} thrown by {@link
     * PortalCaseAccess#resolveForContact}.
     */
    @Test
    void uploadWithANonexistentCaseIdIsA404TheSameWayAsACrossCustomerCase() throws Exception {
        seedTenant();
        var customerId = new UUID[1];
        fixture.runAs(tenant, () ->
                customerId[0] = fixture.createCustomer(tenant, "Nonexistent Case Co " + Uuid7.generate(), null, null, null));
        AppUser contact = portalContact(customerId[0], "nonexistent-case-contact");

        mvc.perform(as(uploadRequest(UUID.randomUUID(), "Ghost.pdf", "OTHER", "COMPANY_SHARED",
                        PDF_BYTES, "application/pdf"), contact))
           .andExpect(status().isNotFound());
    }

    private AppUser portalContact(UUID customerId, String emailPrefix) {
        UUID userId = fixture.createPortalUserForContact(
                tenant, customerId, emailPrefix + "+" + Uuid7.generate() + "@portal-document.example");
        var result = new AppUser[1];
        fixture.runAs(tenant, () -> result[0] = appUsers.findById(userId).orElseThrow());
        return result[0];
    }

    private UUID upload(UUID caseId, String name, String category, String tier,
                        byte[] bytes, String contentType, AppUser actor) throws Exception {
        MvcResult result = mvc.perform(as(uploadRequest(caseId, name, category, tier, bytes, contentType), actor))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private MockHttpServletRequestBuilder uploadRequest(UUID caseId, String name, String category, String tier,
                                                        byte[] bytes, String contentType) {
        String metadata;
        try {
            metadata = JSON.writeValueAsString(Map.of("name", name, "category", category, "visibilityTier", tier));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return multipart(base() + "/cases/" + caseId + "/documents")
                .file(new MockMultipartFile("file", name, contentType, bytes))
                .file(new MockMultipartFile("metadata", "", "application/json",
                        metadata.getBytes(StandardCharsets.UTF_8)));
    }

    private String base() {
        return "/api/t/" + tenantSlug + "/portal";
    }
}
