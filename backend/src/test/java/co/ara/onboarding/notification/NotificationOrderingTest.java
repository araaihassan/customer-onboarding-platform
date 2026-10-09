package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.document.CreateDocumentRequest;
import co.ara.onboarding.document.CreateDocumentRequestRequest;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.document.DocumentReviewService;
import co.ara.onboarding.document.DocumentService;
import co.ara.onboarding.document.ReviewDecision;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CommentResourceType;
import co.ara.onboarding.task.CommentService;
import co.ara.onboarding.task.CreateCommentRequest;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import co.ara.onboarding.task.UpdateTaskRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan amendment 12: every audit action is recorded before the calls that record its
 * consequences, and a notification is a consequence. One test per cause; each later producer
 * task adds its own, in a fresh tenant, and asserts through {@link #assertCauseFirst}.
 */
class NotificationOrderingTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;
    @Autowired NotificationTestSupport support;
    @Autowired CommentService comments;
    @Autowired RequirementService requirements;
    @Autowired DocumentRequestService documentRequests;
    @Autowired DocumentService documents;
    @Autowired DocumentReviewService reviews;

    /** Cause before effect: the cause's audit row is not later than the notification it led to. */
    private void assertCauseFirst(UUID tenant, String cause) {
        var causeAt = ownerJdbc().queryForObject(
                "select max(occurred_at) from audit_event where tenant_id = ? and action = ?",
                java.sql.Timestamp.class, tenant, cause);
        var effectAt = ownerJdbc().queryForObject(
                "select min(occurred_at) from audit_event where tenant_id = ? and action = 'notification.sent'",
                java.sql.Timestamp.class, tenant);
        assertThat(causeAt).isNotNull();
        assertThat(effectAt).isNotNull();
        assertThat(causeAt).isBeforeOrEqualTo(effectAt);
    }

    /** A user who may create and edit tasks (and resolve an assignee) at ALL. */
    private UUID taskManager(UUID t, String email) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        support.grant(t, u, Map.of(
                PermissionKeys.TASK_MANAGE, Scope.ALL, PermissionKeys.TASK_VIEW, Scope.ALL,
                PermissionKeys.CASE_VIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL,
                PermissionKeys.USER_VIEW, Scope.ALL));
        return u;
    }

    private UUID taskViewer(UUID t, String email) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        support.grant(t, u, Map.of(PermissionKeys.TASK_VIEW, Scope.ALL));
        return u;
    }

    @Test
    void reassigningATaskIsRecordedBeforeItsNotification() {
        UUID t = fixture.createTenant("order-task-assigned");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID milestoneId = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 0));
        UUID actor = taskManager(t, "actor@order-task-assigned.test");
        UUID carol = taskViewer(t, "carol@order-task-assigned.test");

        // Created unassigned, so the only notification.sent row is the reassignment's.
        UUID[] taskId = new UUID[1];
        fixture.runAsUser(t, actor, () -> taskId[0] = tasks.create(caseId, new CreateTaskRequest(
                milestoneId, null, "Chase", null, TaskPriority.MEDIUM, null, null)).id());
        fixture.runAsUser(t, actor, () -> tasks.update(taskId[0], new UpdateTaskRequest(
                "Chase", null, TaskPriority.MEDIUM, carol, null, milestoneId)));

        assertThat(support.rowsFor(t, carol)).hasSize(1);
        assertCauseFirst(t, "task.assigned");
    }

    @Test
    void commentingIsRecordedBeforeItsNotification() {
        UUID t = fixture.createTenant("order-comment-added");
        UUID owner = fixture.runAsReturning(t, () -> fixture.createUser(t, "owner@order-comment-added.test"));
        support.grant(t, owner, Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID caseId = fixture.runAsReturning(t, () ->
                sla.openFor(fixture.createCustomerOwnedBy(t, "Acme", owner)));
        UUID author = fixture.runAsReturning(t, () -> fixture.createUser(t, "author@order-comment-added.test"));
        support.grant(t, author, Map.of(PermissionKeys.COMMENT_CREATE, Scope.ALL));

        // A journey comment: the owner is the only candidate and the only notification.sent row.
        fixture.runAsUser(t, author, () -> comments.create(caseId,
                new CreateCommentRequest(CommentResourceType.CASE, caseId, "Kick-off booked")));

        assertThat(support.rowsFor(t, owner)).hasSize(1);
        assertCauseFirst(t, "comment.added");
    }

    @Test
    void completingAMilestoneIsRecordedBeforeItsNotification() {
        UUID t = fixture.createTenant("order-milestone-completed");
        UUID owner = fixture.runAsReturning(t, () -> fixture.createUser(t, "owner@order-milestone-completed.test"));
        support.grant(t, owner, Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID caseId = fixture.runAsReturning(t, () ->
                sla.openFor(fixture.createCustomerOwnedBy(t, "Acme", owner)));
        UUID completer = fixture.runAsReturning(t, () -> fixture.createUser(t, "done@order-milestone-completed.test"));
        support.grant(t, completer, Map.of(PermissionKeys.MILESTONE_COMPLETE, Scope.ALL,
                PermissionKeys.CASE_VIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        UUID rid = fixture.runAsReturning(t, () -> sla.firstRequirementId(caseId));

        // The owner is the only candidate, so the only notification.sent row is the completion's.
        fixture.runAsUser(t, completer, () -> requirements.satisfy(rid, null, null));

        assertThat(support.rowsFor(t, owner)).hasSize(1);
        assertCauseFirst(t, "milestone.completed");
    }

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    /** An internal upload publishes nothing, so it adds no notification.sent row of its own. */
    private UUID internalUpload(UUID t, UUID actor, UUID caseId) {
        UUID[] id = new UUID[1];
        fixture.runAsUser(t, actor, () -> id[0] = documents.upload(caseId,
                new CreateDocumentRequest("Memo.pdf", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new java.io.ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());
        return id[0];
    }

    @Test
    void requestingADocumentIsRecordedBeforeItsNotification() {
        UUID t = fixture.createTenant("order-document-requested");
        UUID owner = fixture.runAsReturning(t, () -> fixture.createUser(t, "owner@order-document-requested.test"));
        support.grant(t, owner, Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID caseId = fixture.runAsReturning(t, () ->
                sla.openFor(fixture.createCustomerOwnedBy(t, "Acme", owner)));
        UUID actor = fixture.runAsReturning(t, () -> fixture.createUser(t, "actor@order-document-requested.test"));
        support.grant(t, actor, Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

        // The owner is the only candidate, so the only notification.sent row is the request's.
        fixture.runAsUser(t, actor, () -> documentRequests.create(caseId,
                new CreateDocumentRequestRequest(DocumentCategory.NDA, "Mutual NDA", null, false, null)));

        assertThat(support.rowsFor(t, owner)).hasSize(1);
        assertCauseFirst(t, "document.requested");
    }

    @Test
    void fulfillingARequestIsRecordedBeforeItsNotification() {
        UUID t = fixture.createTenant("order-document-fulfilled");
        // No grants: the case owner hears about nothing, leaving the requester the only recipient.
        UUID owner = fixture.runAsReturning(t, () -> fixture.createUser(t, "owner@order-document-fulfilled.test"));
        UUID caseId = fixture.runAsReturning(t, () ->
                sla.openFor(fixture.createCustomerOwnedBy(t, "Acme", owner)));
        Map<String, Scope> staff = Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL, PermissionKeys.DOCUMENT_VIEW,
                Scope.ALL, PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL);
        UUID requester = fixture.runAsReturning(t, () -> fixture.createUser(t, "req@order-document-fulfilled.test"));
        support.grant(t, requester, staff);
        UUID staffer = fixture.runAsReturning(t, () -> fixture.createUser(t, "staff@order-document-fulfilled.test"));
        support.grant(t, staffer, staff);
        UUID[] requestId = new UUID[1];
        fixture.runAsUser(t, requester, () -> requestId[0] = documentRequests.create(caseId,
                new CreateDocumentRequestRequest(DocumentCategory.NDA, null, null, false, null)).id());
        UUID documentId = internalUpload(t, staffer, caseId);

        fixture.runAsUser(t, staffer, () -> documentRequests.fulfil(requestId[0], documentId));

        assertThat(support.rowsFor(t, requester)).hasSize(1);
        assertCauseFirst(t, "document.request_fulfilled");
    }

    @Test
    void reviewingIsRecordedBeforeItsNotification() {
        UUID t = fixture.createTenant("order-document-reviewed");
        UUID owner = fixture.runAsReturning(t, () -> fixture.createUser(t, "owner@order-document-reviewed.test"));
        UUID caseId = fixture.runAsReturning(t, () ->
                sla.openFor(fixture.createCustomerOwnedBy(t, "Acme", owner)));
        UUID uploader = fixture.runAsReturning(t, () -> fixture.createUser(t, "up@order-document-reviewed.test"));
        support.grant(t, uploader, Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL, PermissionKeys.DOCUMENT_VIEW,
                Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        UUID reviewer = fixture.runAsReturning(t, () -> fixture.createUser(t, "rev@order-document-reviewed.test"));
        support.grant(t, reviewer, Map.of(PermissionKeys.DOCUMENT_REVIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW,
                Scope.ALL, PermissionKeys.MILESTONE_COMPLETE, Scope.ALL));
        UUID documentId = internalUpload(t, uploader, caseId);

        // The internal uploader is the only recipient, so the only notification.sent row is the decision's.
        fixture.runAsUser(t, reviewer, () -> reviews.review(documentId, 1, ReviewDecision.APPROVED, null));

        assertThat(support.rowsFor(t, uploader)).hasSize(1);
        assertCauseFirst(t, "document.reviewed");
    }
}
