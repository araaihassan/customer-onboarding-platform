package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CommentResourceType;
import co.ara.onboarding.task.CommentService;
import co.ara.onboarding.task.CreateCommentRequest;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import co.ara.onboarding.task.UpdateCommentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** NEW_COMMENT (6B spec 5.2): the task's assignee or the case owner, plus earlier commenters; never the author. */
class CommentNotificationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;
    @Autowired CommentService comments;
    @Autowired NotificationTestSupport support;

    private static final Map<String, Scope> COMMENTER = Map.of(
            PermissionKeys.COMMENT_CREATE, Scope.ALL, PermissionKeys.TASK_VIEW, Scope.ALL,
            PermissionKeys.CASE_VIEW, Scope.ALL);

    private UUID user(UUID t, String email, Map<String, Scope> grants) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        if (!grants.isEmpty()) support.grant(t, u, grants);
        return u;
    }

    private UUID taskAssignedTo(UUID t, UUID caseId, UUID assignee, String slug) {
        UUID milestoneId = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 0));
        UUID manager = user(t, "manager@" + slug + ".test", Map.of(
                PermissionKeys.TASK_MANAGE, Scope.ALL, PermissionKeys.TASK_VIEW, Scope.ALL,
                PermissionKeys.CASE_VIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL,
                PermissionKeys.USER_VIEW, Scope.ALL));
        UUID[] id = new UUID[1];
        fixture.runAsUser(t, manager, () -> id[0] = tasks.create(caseId, new CreateTaskRequest(
                milestoneId, null, "Chase the KYC pack", null, TaskPriority.MEDIUM, assignee, null)).id());
        return id[0];
    }

    private UUID comment(UUID t, UUID author, UUID caseId, CommentResourceType type, UUID resourceId, String body) {
        UUID[] id = new UUID[1];
        fixture.runAsUser(t, author, () -> id[0] = comments.create(caseId,
                new CreateCommentRequest(type, resourceId, body)).id());
        return id[0];
    }

    private List<Map<String, Object>> newComments(UUID t, UUID recipient) {
        return support.rowsFor(t, recipient).stream().filter(r -> "NEW_COMMENT".equals(r.get("type"))).toList();
    }

    private long newCommentCount(UUID t) {
        return support.notifications(t).stream().filter(r -> "NEW_COMMENT".equals(r.get("type"))).count();
    }

    @Test
    void aCommentOnATaskReachesItsAssigneeAndEarlierCommenters() {
        UUID t = fixture.createTenant("cn-task");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID bob = user(t, "bob@cn-task.test", Map.of(PermissionKeys.TASK_VIEW, Scope.ALL));
        UUID carol = user(t, "carol@cn-task.test", COMMENTER);
        UUID dave = user(t, "dave@cn-task.test", COMMENTER);
        UUID taskId = taskAssignedTo(t, caseId, bob, "cn-task");

        comment(t, carol, caseId, CommentResourceType.TASK, taskId, "Sent the reminder");
        UUID daveComment = comment(t, dave, caseId, CommentResourceType.TASK, taskId, "Customer replied today");

        // carol's own comment reached bob; dave's reached bob and carol.
        assertThat(newComments(t, bob)).hasSize(2);
        var toCarol = newComments(t, carol);
        assertThat(toCarol).hasSize(1);
        var n = toCarol.get(0);
        assertThat(n.get("subject_type")).isEqualTo("comment");
        assertThat(n.get("subject_id")).isEqualTo(daveComment);
        assertThat(n.get("case_id")).isEqualTo(caseId);
        assertThat(n.get("tone")).isEqualTo("INFO");
        assertThat(n.get("title")).isEqualTo("New comment on \"Chase the KYC pack\"");
        String daveName = ownerJdbc().queryForObject("select full_name from app_user where id = ?", String.class, dave);
        assertThat(n.get("body")).isEqualTo(daveName + ": Customer replied today");
        UUID customerId = ownerJdbc().queryForObject(
                "select customer_id from onboarding_case where id = ?", UUID.class, caseId);
        assertThat(n.get("link_path")).isEqualTo(Links.caseLink("cn-task", customerId, caseId));
        assertThat(newComments(t, dave)).isEmpty();
        assertThat(newCommentCount(t)).isEqualTo(3);
    }

    @Test
    void aCommentOnTheJourneyReachesTheCaseOwnerAndEarlierCommenters() {
        UUID t = fixture.createTenant("cn-case");
        UUID owner = user(t, "owner@cn-case.test", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID caseId = fixture.runAsReturning(t, () ->
                sla.openFor(fixture.createCustomerOwnedBy(t, "Acme", owner)));
        UUID carol = user(t, "carol@cn-case.test", COMMENTER);
        UUID dave = user(t, "dave@cn-case.test", COMMENTER);
        // A task viewer without case.view is neither a candidate nor able to see a journey comment.
        UUID taskOnly = user(t, "taskonly@cn-case.test", Map.of(PermissionKeys.TASK_VIEW, Scope.ALL));

        comment(t, carol, caseId, CommentResourceType.CASE, caseId, "Kick-off booked");
        UUID daveComment = comment(t, dave, caseId, CommentResourceType.CASE, caseId, "Agenda attached");

        assertThat(newComments(t, owner)).hasSize(2);
        var toCarol = newComments(t, carol);
        assertThat(toCarol).hasSize(1);
        assertThat(toCarol.get(0).get("subject_id")).isEqualTo(daveComment);
        assertThat(toCarol.get(0).get("title")).isEqualTo("New comment on SLA case");
        assertThat((String) toCarol.get(0).get("body")).endsWith(": Agenda attached");
        assertThat(newComments(t, dave)).isEmpty();
        assertThat(newComments(t, taskOnly)).isEmpty();
        assertThat(newCommentCount(t)).isEqualTo(3);
    }

    /** The narrowest case.view scope: ASSIGNED reaches the owner through their OWNER participant row. */
    @Test
    void aCaseOwnerHoldingCaseViewOnlyAtAssignedScopeIsNotified() {
        UUID t = fixture.createTenant("cn-case-assigned");
        UUID owner = user(t, "owner@cn-case-assigned.test", Map.of(PermissionKeys.CASE_VIEW, Scope.ASSIGNED));
        UUID caseId = fixture.runAsReturning(t, () ->
                sla.openFor(fixture.createCustomerOwnedBy(t, "Acme", owner)));
        UUID dave = user(t, "dave@cn-case-assigned.test", COMMENTER);

        comment(t, dave, caseId, CommentResourceType.CASE, caseId, "Agenda attached");

        assertThat(newComments(t, owner)).hasSize(1);
    }

    /** The narrowest task.view scope: ASSIGNED is assignee_id = recipient. */
    @Test
    void anAssigneeHoldingTaskViewOnlyAtAssignedScopeIsNotified() {
        UUID t = fixture.createTenant("cn-task-assigned");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID bob = user(t, "bob@cn-task-assigned.test", Map.of(PermissionKeys.TASK_VIEW, Scope.ASSIGNED));
        UUID dave = user(t, "dave@cn-task-assigned.test", COMMENTER);
        UUID taskId = taskAssignedTo(t, caseId, bob, "cn-task-assigned");

        comment(t, dave, caseId, CommentResourceType.TASK, taskId, "Customer replied today");

        assertThat(newComments(t, bob)).hasSize(1);
    }

    @Test
    void theAuthorIsNeverNotifiedEvenAsAnEarlierCommenter() {
        UUID t = fixture.createTenant("cn-author");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID dave = user(t, "dave@cn-author.test", COMMENTER);
        UUID taskId = taskAssignedTo(t, caseId, dave, "cn-author");   // dave is the assignee too

        comment(t, dave, caseId, CommentResourceType.TASK, taskId, "First");
        comment(t, dave, caseId, CommentResourceType.TASK, taskId, "Second");

        assertThat(newCommentCount(t)).isZero();
    }

    /** An assignee who also commented earlier is one candidate, so one row per comment. */
    @Test
    void anAssigneeWhoAlsoCommentedEarlierGetsOneRow() {
        UUID t = fixture.createTenant("cn-dedupe");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID bob = user(t, "bob@cn-dedupe.test", COMMENTER);
        UUID dave = user(t, "dave@cn-dedupe.test", COMMENTER);
        UUID taskId = taskAssignedTo(t, caseId, bob, "cn-dedupe");

        comment(t, bob, caseId, CommentResourceType.TASK, taskId, "On it");
        comment(t, bob, caseId, CommentResourceType.TASK, taskId, "Still on it");
        comment(t, dave, caseId, CommentResourceType.TASK, taskId, "Thanks");

        assertThat(newComments(t, bob)).hasSize(1);
        assertThat(newComments(t, dave)).isEmpty();
    }

    @Test
    void aCommenterWhoLostAccessIsNotNotified() {
        UUID t = fixture.createTenant("cn-revoked");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID bob = user(t, "bob@cn-revoked.test", Map.of(PermissionKeys.TASK_VIEW, Scope.ALL));
        UUID carol = fixture.runAsReturning(t, () -> fixture.createUser(t, "carol@cn-revoked.test"));
        UUID carolsRole = support.grant(t, carol, COMMENTER);
        UUID dave = user(t, "dave@cn-revoked.test", COMMENTER);
        UUID taskId = taskAssignedTo(t, caseId, bob, "cn-revoked");

        comment(t, carol, caseId, CommentResourceType.TASK, taskId, "Sent the reminder");
        support.revoke(t, carol, carolsRole);
        comment(t, dave, caseId, CommentResourceType.TASK, taskId, "Customer replied today");

        assertThat(newComments(t, carol)).isEmpty();
        assertThat(newComments(t, bob)).hasSize(2);
    }

    @Test
    void editingACommentNotifiesNobody() {
        UUID t = fixture.createTenant("cn-edit");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID bob = user(t, "bob@cn-edit.test", Map.of(PermissionKeys.TASK_VIEW, Scope.ALL));
        UUID dave = user(t, "dave@cn-edit.test", COMMENTER);
        UUID taskId = taskAssignedTo(t, caseId, bob, "cn-edit");

        UUID commentId = comment(t, dave, caseId, CommentResourceType.TASK, taskId, "Typo");
        fixture.runAsUser(t, dave, () -> comments.update(commentId, new UpdateCommentRequest("Fixed")));

        assertThat(newComments(t, bob)).hasSize(1);
    }
}
