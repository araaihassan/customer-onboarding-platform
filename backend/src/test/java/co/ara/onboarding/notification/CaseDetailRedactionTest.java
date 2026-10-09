package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.scheduling.NotificationSweepJob;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CommentResourceType;
import co.ara.onboarding.task.CommentService;
import co.ara.onboarding.task.CreateCommentRequest;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final-review Important 1: a notification gated on a task (task.view) must not name the case or
 * the customer to a recipient who cannot view that case, nor link them to a case page that would
 * 404. Both recipients here hold their grants at the narrowest scope, ASSIGNED: the hidden one
 * task.view only (a task they are the assignee of, on a case they do not participate in), the
 * full one task.view plus case.view through a real participant row (the positive control).
 */
class CaseDetailRedactionTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired JourneyFixtures journey;
    @Autowired TaskService tasks;
    @Autowired CommentService comments;
    @Autowired BusinessCalendar calendar;
    @Autowired NotificationSweepJob sweep;
    @Autowired NotificationTestSupport support;

    private record World(String slug, UUID t, UUID caseId, UUID milestoneId, String caseName, String customerName,
                         UUID actor, UUID hidden, UUID full) {}

    private World world(String slug) {
        UUID t = fixture.createTenant(slug);
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID milestoneId = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 0));
        String caseName = ownerJdbc().queryForObject("select name from onboarding_case where id = ?", String.class, caseId);
        String customerName = ownerJdbc().queryForObject(
                "select c.display_name from customer c join onboarding_case k on k.customer_id = c.id where k.id = ?",
                String.class, caseId);
        UUID actor = fixture.runAsReturning(t, () -> fixture.createUser(t, "actor@" + slug + ".test"));
        UUID hidden = fixture.runAsReturning(t, () -> fixture.createUser(t, "hidden@" + slug + ".test"));
        UUID full = fixture.runAsReturning(t, () -> fixture.createUser(t, "full@" + slug + ".test"));
        support.grant(t, actor, Map.of(
                PermissionKeys.TASK_MANAGE, Scope.ALL, PermissionKeys.TASK_VIEW, Scope.ALL,
                PermissionKeys.TASK_COMPLETE, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL,
                PermissionKeys.WORKFLOW_VIEW, Scope.ALL, PermissionKeys.USER_VIEW, Scope.ALL,
                PermissionKeys.COMMENT_CREATE, Scope.ALL));
        support.grant(t, hidden, Map.of(PermissionKeys.TASK_VIEW, Scope.ASSIGNED));
        support.grant(t, full, Map.of(PermissionKeys.TASK_VIEW, Scope.ASSIGNED, PermissionKeys.CASE_VIEW, Scope.ASSIGNED));
        fixture.runAs(t, () -> journey.addParticipant(t, caseId, full, RelationshipType.PARTICIPANT,
                ParticipantStatus.ACTIVE));
        ownerJdbc().update("insert into business_calendar (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        ownerJdbc().update("insert into notification_policy (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        ownerJdbc().update("delete from deadline_horizon where tenant_id = ? and kind = 'TASK_DUE'", t);
        ownerJdbc().update("insert into deadline_horizon (id, tenant_id, kind, lead_days, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, 'TASK_DUE', 2, now(), now())", t);
        return new World(slug, t, caseId, milestoneId, caseName, customerName, actor, hidden, full);
    }

    private UUID taskFor(World w, UUID assignee, LocalDate due) {
        UUID[] id = new UUID[1];
        fixture.runAsUser(w.t(), w.actor(), () -> id[0] = tasks.create(w.caseId(), new CreateTaskRequest(
                w.milestoneId(), null, "Chase the KYC pack", null, TaskPriority.MEDIUM, assignee, due)).id());
        return id[0];
    }

    private LocalDate today(World w) { return fixture.runAsReturning(w.t(), () -> calendar.today()); }

    private Map<String, Object> only(World w, UUID recipient, String type) {
        var rows = support.rowsFor(w.t(), recipient).stream().filter(r -> type.equals(r.get("type"))).toList();
        assertThat(rows).as(type + " rows for the recipient").hasSize(1);
        return rows.get(0);
    }

    private List<Map<String, Object>> outboxFor(World w, UUID recipient) {
        return support.outbox(w.t()).stream().filter(r -> recipient.equals(r.get("recipient_user_id"))).toList();
    }

    private String caseLink(World w) {
        UUID customerId = ownerJdbc().queryForObject(
                "select customer_id from onboarding_case where id = ?", UUID.class, w.caseId());
        return Links.caseLink(w.slug(), customerId, w.caseId());
    }

    private void assertRedacted(World w, Map<String, Object> row) {
        String text = row.get("title") + " " + row.get("body");
        assertThat(text).doesNotContain(w.caseName()).doesNotContain(w.customerName());
        assertThat((String) row.get("link_path")).isEqualTo(Links.workLink(w.slug())).doesNotContain("/cases/");
    }

    private void assertFull(World w, Map<String, Object> row) {
        assertThat((String) row.get("body")).contains(w.caseName());
        assertThat(row.get("link_path")).isEqualTo(caseLink(w));
    }

    @Test
    void anAssigneeWhoCannotViewTheCaseIsNotToldItsNameOrTheCustomers() {
        var w = world("red-assign");
        taskFor(w, w.hidden(), LocalDate.of(2026, 11, 2));
        var row = only(w, w.hidden(), "TASK_ASSIGNED");
        assertRedacted(w, row);
        assertThat((String) row.get("body")).contains("Chase the KYC pack").contains("due 2026-11-02");
        var mail = outboxFor(w, w.hidden());
        assertThat(mail).hasSize(1);
        assertThat(mail.get(0).get("subject") + " " + mail.get(0).get("body"))
                .doesNotContain(w.caseName()).doesNotContain(w.customerName());
        assertThat(mail.get(0).get("link_path")).isEqualTo(Links.workLink(w.slug()));

        // Positive control: an ASSIGNED-scoped participant of the case gets the full text and the case link.
        taskFor(w, w.full(), LocalDate.of(2026, 11, 2));
        var fullRow = only(w, w.full(), "TASK_ASSIGNED");
        assertFull(w, fullRow);
        assertThat((String) fullRow.get("body")).contains(w.customerName());
        assertThat(outboxFor(w, w.full()).get(0).get("body").toString()).contains(w.caseName());
    }

    @Test
    void anOverdueTaskOnACaseTheAssigneeCannotViewIsAnnouncedWithoutTheCase() {
        var w = world("red-overdue");
        taskFor(w, w.hidden(), today(w).minusDays(1));
        taskFor(w, w.full(), today(w).minusDays(1));
        sweep.runOne(w.t());
        assertRedacted(w, only(w, w.hidden(), "TASK_OVERDUE"));
        assertThat(outboxFor(w, w.hidden()).stream().filter(r -> r.get("subject").toString().startsWith("Overdue"))
                .map(r -> r.get("subject") + " " + r.get("body")).toList())
                .hasSize(1).allSatisfy(s -> assertThat(s).doesNotContain(w.caseName()).doesNotContain(w.customerName()));
        assertFull(w, only(w, w.full(), "TASK_OVERDUE"));
    }

    @Test
    void aTaskDeadlineOnACaseTheAssigneeCannotViewIsAnnouncedWithoutTheCase() {
        var w = world("red-deadline");
        LocalDate due = fixture.runAsReturning(w.t(), () -> calendar.plusBusinessDays(calendar.today(), 1));
        taskFor(w, w.hidden(), due);
        taskFor(w, w.full(), due);
        sweep.runOne(w.t());
        assertRedacted(w, only(w, w.hidden(), "DEADLINE_APPROACHING"));
        var fullRow = only(w, w.full(), "DEADLINE_APPROACHING");
        assertFull(w, fullRow);
        assertThat((String) fullRow.get("body")).contains(w.customerName());
    }

    @Test
    void aTaskCommentReachesAnAssigneeWhoCannotViewTheCaseWithoutTheCommentOrTheCase() {
        var w = world("red-comment");
        UUID hiddenTask = taskFor(w, w.hidden(), null);
        UUID fullTask = taskFor(w, w.full(), null);
        String text = "Customer sent the passport scan";
        fixture.runAsUser(w.t(), w.actor(), () -> {
            comments.create(w.caseId(), new CreateCommentRequest(CommentResourceType.TASK, hiddenTask, text));
            comments.create(w.caseId(), new CreateCommentRequest(CommentResourceType.TASK, fullTask, text));
        });
        var row = only(w, w.hidden(), "NEW_COMMENT");
        assertRedacted(w, row);
        assertThat((String) row.get("body")).doesNotContain(text);   // a comment is read under case.view
        var fullRow = only(w, w.full(), "NEW_COMMENT");
        assertThat((String) fullRow.get("body")).contains(text);
        assertThat(fullRow.get("link_path")).isEqualTo(caseLink(w));
    }
}
