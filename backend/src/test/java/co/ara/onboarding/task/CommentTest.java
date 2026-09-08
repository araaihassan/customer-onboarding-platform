package co.ara.onboarding.task;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 23: internal comments on a task or a journey, polymorphic over
 * {@link CommentResourceType}, always resolved through the case (design
 * spec 3.3, 4.3, 6.1). Reading is gated case.view -- there is deliberately
 * no comment.view -- while posting and editing are both gated comment.create,
 * with editing additionally refused for anyone but the comment's own author,
 * regardless of scope.
 */
class CommentTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired CommentService comments;
    @Autowired RoleService roles;

    @Test
    void aCommentOnATaskAndOnAJourneyBothResolveThroughTheCase() {
        UUID tenant = fixture.createTenant("comment-resolve-through-case");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID taskId = taskOn(caseId);

            UUID onTask = comments.create(caseId, new CreateCommentRequest(
                    CommentResourceType.TASK, taskId, "Chased the customer today")).id();
            UUID onCase = comments.create(caseId, new CreateCommentRequest(
                    CommentResourceType.CASE, caseId, "Kickoff moved to Tuesday")).id();

            assertThat(comments.forResource(caseId, CommentResourceType.TASK, taskId))
                    .extracting(CommentView::id).containsExactly(onTask);
            assertThat(comments.forResource(caseId, CommentResourceType.CASE, caseId))
                    .extracting(CommentView::id).containsExactly(onCase);
        });
    }

    /** Author-only, and no breadth of scope widens it (design spec 6.1). */
    @Test
    void anotherUsersCommentCannotBeEditedEvenAtAllScope() {
        UUID tenant = fixture.createTenant("comment-author-only");
        var caseId = new UUID[1];
        var otherUser = new UUID[1];
        var adminAtAllScope = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = simpleCase(tenant);
            otherUser[0] = fixture.createUser(tenant, "other@example.com");
            grant(otherUser[0], Map.of(
                    PermissionKeys.COMMENT_CREATE, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL));
            adminAtAllScope[0] = fixture.createUser(tenant, "wide-editor@example.com");
            grant(adminAtAllScope[0], Map.of(
                    PermissionKeys.COMMENT_CREATE, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL));
        });

        var theirs = new UUID[1];
        fixture.runAsUser(tenant, otherUser[0], () ->
                theirs[0] = comments.create(caseId[0], new CreateCommentRequest(
                        CommentResourceType.CASE, caseId[0], "Theirs")).id());

        assertThatThrownBy(() -> fixture.runAsUser(tenant, adminAtAllScope[0], () ->
                comments.update(theirs[0], new UpdateCommentRequest("Rewritten"))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void editingMarksTheCommentEdited() {
        UUID tenant = fixture.createTenant("comment-edited-marker");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID mineId = comments.create(caseId, new CreateCommentRequest(
                    CommentResourceType.CASE, caseId, "Original")).id();

            comments.update(mineId, new UpdateCommentRequest("Corrected"));

            assertThat(comments.get(mineId).editedAt()).isNotNull();
        });
    }

    /** No comment.view exists -- case.view alone is enough to read the discussion. */
    @Test
    void aCommentIsReadableByAnyoneWhoCanSeeTheJourney() {
        UUID tenant = fixture.createTenant("comment-read-via-case-view");
        var caseId = new UUID[1];
        var caseViewerOnly = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = simpleCase(tenant);
            comments.create(caseId[0], new CreateCommentRequest(
                    CommentResourceType.CASE, caseId[0], "Visible to viewers"));

            caseViewerOnly[0] = fixture.createUser(tenant, "viewer@example.com");
            grant(caseViewerOnly[0], Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        });

        fixture.runAsUser(tenant, caseViewerOnly[0], () ->
                assertThat(comments.forResource(caseId[0], CommentResourceType.CASE, caseId[0]))
                        .isNotEmpty());
    }

    @Test
    void aCrossTenantCaseIdYieldsNoComments() {
        UUID tenantA = fixture.createTenant("comment-cross-tenant-a");
        UUID tenantB = fixture.createTenant("comment-cross-tenant-b");
        var tenantACaseId = new UUID[1];
        fixture.runAs(tenantA, () -> {
            tenantACaseId[0] = simpleCase(tenantA);
            comments.create(tenantACaseId[0], new CreateCommentRequest(
                    CommentResourceType.CASE, tenantACaseId[0], "Tenant A only"));
        });

        assertThatThrownBy(() -> fixture.runAs(tenantB, () ->
                comments.forResource(tenantACaseId[0], CommentResourceType.CASE, tenantACaseId[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * resourceId must actually belong to the resolved case -- an ad-hoc task
     * living under a DIFFERENT case is refused as not found, the same
     * "does the id actually belong to what it claims" shape as
     * TaskService.create's own case/milestone mismatch guard.
     */
    @Test
    void aTaskFromADifferentCaseIsRefusedAsNotFound() {
        UUID tenant = fixture.createTenant("comment-cross-case-task");
        var caseId = new UUID[1];
        var otherCasesTaskId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = simpleCase(tenant);
            UUID otherCaseId = simpleCase(tenant);
            otherCasesTaskId[0] = taskOn(otherCaseId);
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> comments.create(caseId[0],
                new CreateCommentRequest(CommentResourceType.TASK, otherCasesTaskId[0], "Wrong case"))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** A CASE comment whose resourceId names a different case is refused the same way. */
    @Test
    void aCaseCommentNamingADifferentCaseIsRefusedAsNotFound() {
        UUID tenant = fixture.createTenant("comment-mismatched-case-id");
        var caseId = new UUID[1];
        var otherCaseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = simpleCase(tenant);
            otherCaseId[0] = simpleCase(tenant);
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> comments.create(caseId[0],
                new CreateCommentRequest(CommentResourceType.CASE, otherCaseId[0], "Wrong case id"))))
                .isInstanceOf(NoSuchElementException.class);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID simpleCase(UUID tenant) {
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(
                customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    private UUID firstMilestone(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).id();
    }

    /** An ad-hoc task on a case's first milestone. */
    private UUID taskOn(UUID caseId) {
        UUID milestoneId = firstMilestone(caseId);
        return tasks.create(caseId, new CreateTaskRequest(
                milestoneId, null, "Ad-hoc", null, TaskPriority.LOW, null, null)).id();
    }
}
