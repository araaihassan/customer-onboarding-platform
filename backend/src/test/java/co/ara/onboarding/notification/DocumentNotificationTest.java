package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.document.CreateDocumentRequest;
import co.ara.onboarding.document.CreateDocumentRequestRequest;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.document.DocumentReviewService;
import co.ara.onboarding.document.DocumentService;
import co.ara.onboarding.document.ReviewDecision;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.document;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOCUMENT_REQUESTED, DOCUMENT_UPLOADED and DOCUMENT_DECIDED (spec 5.2). The upload and the
 * decision are gated document.view through RecipientAccess, so the document audience filter
 * (targeting) binds each recipient exactly as it binds a request -- Review Focus 1.
 */
class DocumentNotificationTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired CaseRepository caseRepository;
    @Autowired NotificationTestSupport support;
    @Autowired DocumentRequestService requests;
    @Autowired DocumentService documents;
    @Autowired DocumentReviewService reviews;
    @Autowired CustomerContactRepository contacts;

    // ---- arrangement helpers -------------------------------------------------------------

    private UUID user(UUID t, String email, Map<String, Scope> grants) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        if (!grants.isEmpty()) support.grant(t, u, grants);
        return u;
    }

    private UUID userIn(UUID t, String email, UUID department, Map<String, Scope> grants) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUserInDepartment(t, email, department));
        if (!grants.isEmpty()) support.grant(t, u, grants);
        return u;
    }

    /** May create, list and fulfil requests and see documents (write scope needs workflow.view). */
    private static Map<String, Scope> requester() {
        return Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL, PermissionKeys.DOCUMENT_VIEW, Scope.ALL,
                PermissionKeys.WORKFLOW_VIEW, Scope.ALL);
    }

    private UUID ownedCase(UUID t, UUID owner, String customer) {
        return fixture.runAsReturning(t, () -> sla.openFor(fixture.createCustomerOwnedBy(t, customer, owner)));
    }

    private UUID requestOn(UUID t, UUID actor, UUID caseId, String description) {
        UUID[] id = new UUID[1];
        fixture.runAsUser(t, actor, () -> id[0] = requests.create(caseId, new CreateDocumentRequestRequest(
                DocumentCategory.COMPANY_REGISTRATION, description, null, false, null)).id());
        return id[0];
    }

    private UUID portalUpload(UUID t, UUID caseId, String name) {
        UUID customerId = fixture.runAsReturning(t, () -> caseRepository.findById(caseId).orElseThrow().getCustomerId());
        UUID portalUser = fixture.createPortalUserForContact(t, customerId,
                "contact+" + Uuid7.generate() + "@portal.example");
        UUID contactId = fixture.runAsReturning(t, () -> contacts.findByUserId(portalUser).orElseThrow().getId());
        UUID[] id = new UUID[1];
        fixture.runAsUser(t, portalUser, () -> id[0] = documents.uploadFromPortal(
                caseRepository.findById(caseId).orElseThrow(), contactId,
                new CreateDocumentRequest(name, DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());
        return id[0];
    }

    private UUID internalUpload(UUID t, UUID actor, UUID caseId, String name, UUID targetDepartment) {
        UUID[] id = new UUID[1];
        fixture.runAsUser(t, actor, () -> id[0] = documents.upload(caseId,
                new CreateDocumentRequest(name, DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        targetDepartment, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());
        return id[0];
    }

    private List<Map<String, Object>> rowsOfType(UUID t, UUID recipient, NotificationType type) {
        return support.rowsFor(t, recipient).stream().filter(r -> type.name().equals(r.get("type"))).toList();
    }

    // ---- DOCUMENT_REQUESTED --------------------------------------------------------------

    @Test
    void aNewRequestNotifiesTheCaseOwner() {
        UUID t = fixture.createTenant("doc-notify-requested");
        UUID owner = user(t, "owner@doc-notify-requested.test", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID actor = user(t, "actor@doc-notify-requested.test", Map.of(
                PermissionKeys.DOCUMENT_REQUEST, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        UUID caseId = ownedCase(t, owner, "Acme");

        UUID requestId = requestOn(t, actor, caseId, "Signed 2025 return");

        var rows = support.rowsFor(t, owner);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.get("type")).isEqualTo("DOCUMENT_REQUESTED");
        assertThat(row.get("subject_type")).isEqualTo("document_request");
        assertThat(row.get("subject_id")).isEqualTo(requestId);
        assertThat(row.get("case_id")).isEqualTo(caseId);
        assertThat(row.get("tone")).isEqualTo("INFO");
        assertThat(row.get("title")).isEqualTo("Document requested on SLA case");
        // humanised category, never the raw enum
        assertThat(row.get("body")).isEqualTo("Company registration - Signed 2025 return");
        assertThat(support.rowsFor(t, actor)).isEmpty();
    }

    @Test
    void aRequestReachesAnOwnerHoldingCaseViewOnlyAtAssignedScope() {
        UUID t = fixture.createTenant("doc-notify-requested-assigned");
        UUID owner = user(t, "owner@doc-notify-requested-assigned.test",
                Map.of(PermissionKeys.CASE_VIEW, Scope.ASSIGNED));
        UUID actor = user(t, "actor@doc-notify-requested-assigned.test", Map.of(
                PermissionKeys.DOCUMENT_REQUEST, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        UUID caseId = ownedCase(t, owner, "Acme");

        requestOn(t, actor, caseId, null);

        assertThat(rowsOfType(t, owner, NotificationType.DOCUMENT_REQUESTED)).hasSize(1);
    }

    @Test
    void aRequestDoesNotReachAnOwnerWithoutCaseView() {
        UUID t = fixture.createTenant("doc-notify-requested-noview");
        UUID owner = user(t, "owner@doc-notify-requested-noview.test", Map.of());
        UUID actor = user(t, "actor@doc-notify-requested-noview.test", Map.of(
                PermissionKeys.DOCUMENT_REQUEST, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        UUID caseId = ownedCase(t, owner, "Acme");

        requestOn(t, actor, caseId, null);

        assertThat(support.rowsFor(t, owner)).isEmpty();
    }

    @Test
    void anInstantiatedRequestNotifiesTheCaseOwnerToo() {
        UUID t = fixture.createTenant("doc-notify-instantiated");
        UUID owner = user(t, "owner@doc-notify-instantiated.test", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));

        // The fixture administrator opens the case, so the creator is not the owner.
        UUID caseId = fixture.runAsReturning(t, () -> {
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(
                    SlaTestSupport.slaStage("s1", "Stage One", List.of(milestone("m1", "Milestone One", 1, List.of(),
                            List.of(document("Provide NDA", "NDA")))), 3, false)), List.of(), 0L));
            UUID customerId = fixture.createCustomerOwnedBy(t, "Acme", owner);
            return cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId),
                    "Instantiated case", Map.of())).id();
        });

        var rows = rowsOfType(t, owner, NotificationType.DOCUMENT_REQUESTED);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("case_id")).isEqualTo(caseId);
        assertThat(rows.get(0).get("title")).isEqualTo("Document requested on Instantiated case");
        assertThat(rows.get(0).get("body")).isEqualTo("Nda - Provide NDA");
    }

    // ---- DOCUMENT_UPLOADED ---------------------------------------------------------------

    @Test
    void aPortalUploadNotifiesTheRequesterAndTheCaseOwner() {
        UUID t = fixture.createTenant("doc-notify-uploaded");
        UUID owner = user(t, "owner@doc-notify-uploaded.test", Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
        UUID requesterId = user(t, "req@doc-notify-uploaded.test", requester());
        UUID fulfiller = user(t, "ful@doc-notify-uploaded.test", requester());
        UUID caseId = ownedCase(t, owner, "Acme");
        UUID requestId = requestOn(t, requesterId, caseId, null);

        UUID documentId = portalUpload(t, caseId, "Tax 2025.pdf");
        // The upload alone reaches the owner (no request is linked to the document yet).
        assertThat(rowsOfType(t, owner, NotificationType.DOCUMENT_UPLOADED)).hasSize(1);

        // Staff then fulfil the request with it: a second publish, collapsed by the dedupe key.
        fixture.runAsUser(t, fulfiller, () -> requests.fulfil(requestId, documentId));

        var ownerRows = rowsOfType(t, owner, NotificationType.DOCUMENT_UPLOADED);
        var requesterRows = rowsOfType(t, requesterId, NotificationType.DOCUMENT_UPLOADED);
        assertThat(ownerRows).hasSize(1);
        assertThat(requesterRows).hasSize(1);
        var row = requesterRows.get(0);
        assertThat(row.get("subject_type")).isEqualTo("document");
        assertThat(row.get("subject_id")).isEqualTo(documentId);
        assertThat(row.get("case_id")).isEqualTo(caseId);
        assertThat(row.get("title")).isEqualTo("Acme uploaded a document");
        assertThat(row.get("body")).isEqualTo("Tax 2025.pdf on SLA case.");
        assertThat(row.get("dedupe_key")).isEqualTo("UPLOADED:" + documentId);
        assertThat(rowsOfType(t, fulfiller, NotificationType.DOCUMENT_UPLOADED)).isEmpty();
    }

    /** Review Focus 1: targeting binds a recipient holding document.view at ALL. */
    @Test
    void aTargetedUploadDoesNotReachAnOwnerOutsideItsAudience() {
        UUID t = fixture.createTenant("doc-notify-targeted");
        UUID[] depts = new UUID[2];
        fixture.runAs(t, () -> {
            depts[0] = fixture.createDepartment(t, "Sales");
            depts[1] = fixture.createDepartment(t, "Legal");
        });
        UUID owner = userIn(t, "owner@doc-notify-targeted.test", depts[0], Map.of(
                PermissionKeys.DOCUMENT_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID requesterId = userIn(t, "req@doc-notify-targeted.test", depts[1], requester());
        // In Legal too, or fulfil itself could not resolve the targeted document.
        Map<String, Scope> staff = new java.util.HashMap<>(requester());
        staff.put(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL);
        UUID staffer = userIn(t, "staff@doc-notify-targeted.test", depts[1], staff);
        UUID caseId = ownedCase(t, owner, "Acme");
        UUID requestId = requestOn(t, requesterId, caseId, null);

        UUID documentId = internalUpload(t, staffer, caseId, "Legal memo.pdf", depts[1]);
        fixture.runAsUser(t, staffer, () -> requests.fulfil(requestId, documentId));

        assertThat(rowsOfType(t, owner, NotificationType.DOCUMENT_UPLOADED)).isEmpty();
        assertThat(rowsOfType(t, requesterId, NotificationType.DOCUMENT_UPLOADED)).hasSize(1);
    }

    // ---- DOCUMENT_DECIDED ----------------------------------------------------------------

    @Test
    void aReviewNotifiesTheRequesterAndOwnerWithTheDecisionTone() {
        UUID t = fixture.createTenant("doc-notify-reviewed");
        UUID owner = user(t, "owner@doc-notify-reviewed.test", Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
        UUID requesterId = user(t, "req@doc-notify-reviewed.test", requester());
        UUID reviewer = user(t, "rev@doc-notify-reviewed.test", Map.of(PermissionKeys.DOCUMENT_REVIEW, Scope.ALL,
                PermissionKeys.DOCUMENT_REQUEST, Scope.ALL, PermissionKeys.DOCUMENT_VIEW, Scope.ALL,
                PermissionKeys.WORKFLOW_VIEW, Scope.ALL, PermissionKeys.MILESTONE_COMPLETE, Scope.ALL));
        UUID caseId = ownedCase(t, owner, "Acme");
        UUID requestId = requestOn(t, requesterId, caseId, null);
        UUID documentId = portalUpload(t, caseId, "Tax 2025.pdf");
        fixture.runAsUser(t, reviewer, () -> requests.fulfil(requestId, documentId));

        fixture.runAsUser(t, reviewer, () -> reviews.review(documentId, 1, ReviewDecision.APPROVED, null));

        var approved = rowsOfType(t, requesterId, NotificationType.DOCUMENT_DECIDED);
        assertThat(approved).hasSize(1);
        assertThat(approved.get(0).get("tone")).isEqualTo("OK");
        assertThat((String) approved.get(0).get("title")).isEqualTo("Tax 2025.pdf approved");
        assertThat(approved.get(0).get("body")).isEqualTo("Version 1 on SLA case.");
        assertThat(rowsOfType(t, owner, NotificationType.DOCUMENT_DECIDED)).hasSize(1);

        // A second look changes the decision; the new decision is what the new row says.
        fixture.runAsUser(t, reviewer, () -> reviews.review(documentId, 1, ReviewDecision.REJECTED, "Unsigned"));

        var both = rowsOfType(t, requesterId, NotificationType.DOCUMENT_DECIDED);
        assertThat(both).hasSize(2);
        assertThat(both.get(1).get("tone")).isEqualTo("RISK");
        assertThat((String) both.get(1).get("title")).contains("rejected");
        var ownerRows = rowsOfType(t, owner, NotificationType.DOCUMENT_DECIDED);
        assertThat(ownerRows).hasSize(2);
        assertThat(ownerRows.get(1).get("tone")).isEqualTo("RISK");
        assertThat(rowsOfType(t, reviewer, NotificationType.DOCUMENT_DECIDED)).isEmpty();
    }

    /**
     * Narrowest document.view scope: ASSIGNED is uploaded_by = recipient, so an internal uploader
     * holding only that hears about the decision on their own version, while an owner holding the
     * same ASSIGNED grant (not the uploader) does not.
     */
    @Test
    void aReviewReachesAnInternalUploaderHoldingDocumentViewOnlyAtAssignedScope() {
        UUID t = fixture.createTenant("doc-notify-reviewed-assigned");
        UUID owner = user(t, "owner@doc-notify-reviewed-assigned.test",
                Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.ASSIGNED));
        UUID uploader = user(t, "up@doc-notify-reviewed-assigned.test", Map.of(
                PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL, PermissionKeys.DOCUMENT_VIEW, Scope.ASSIGNED,
                PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        UUID reviewer = user(t, "rev@doc-notify-reviewed-assigned.test", Map.of(
                PermissionKeys.DOCUMENT_REVIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL,
                PermissionKeys.MILESTONE_COMPLETE, Scope.ALL));
        UUID caseId = ownedCase(t, owner, "Acme");
        UUID documentId = internalUpload(t, uploader, caseId, "Board pack.pdf", null);

        fixture.runAsUser(t, reviewer, () -> reviews.review(documentId, 1, ReviewDecision.APPROVED, null));

        assertThat(rowsOfType(t, uploader, NotificationType.DOCUMENT_DECIDED)).hasSize(1);
        assertThat(rowsOfType(t, owner, NotificationType.DOCUMENT_DECIDED)).isEmpty();
    }
}
