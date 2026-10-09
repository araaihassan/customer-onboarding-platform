package co.ara.onboarding.notification;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.Task;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6B spec 5.3 steps 1-6, driven directly: a TASK_ASSIGNED draft about a real task, delivered as
 * the actor, asserted on rows. The narrow-scope tests exist because RecipientAccess needs a
 * registered descriptor for every entity type a Visibility names once the recipient's scope is
 * narrower than ALL -- an ALL-only test would never reach the descriptor at all (Task 8 note).
 */
class NotificationPipelineTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired NotificationPipeline pipeline;
    @Autowired NotificationTestSupport support;
    @Autowired RoleService roles;
    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;

    record World(UUID tenant, UUID actor, UUID viewer, UUID blind, UUID caseId, UUID taskId) {}

    /** viewer and actor hold TASK_VIEW and CASE_VIEW at ALL; blind holds nothing. */
    private World world(String slug) {
        UUID t = fixture.createTenant(slug);
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID actor = fixture.runAsReturning(t, () -> fixture.createUser(t, "actor@" + slug + ".test"));
        UUID viewer = fixture.runAsReturning(t, () -> fixture.createUser(t, "viewer@" + slug + ".test"));
        UUID blind = fixture.runAsReturning(t, () -> fixture.createUser(t, "blind@" + slug + ".test"));
        for (UUID u : List.of(actor, viewer)) {
            grant(t, u, Map.of(PermissionKeys.TASK_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL));
        }
        UUID taskId = fixture.runAsReturning(t, () -> tasks.create(caseId, new CreateTaskRequest(
                sla.milestoneIdAt(caseId, 0), null, "Chase", null, TaskPriority.MEDIUM, null, null)).id());
        return new World(t, actor, viewer, blind, caseId, taskId);
    }

    private void grant(UUID t, UUID user, Map<String, Scope> grants) {
        fixture.runAs(t, () -> roles.assignRole(user, roles.createRole("r-" + user, "", grants)));
    }

    private NotificationPipeline.Draft draft(World w, String dedupe) {
        return new NotificationPipeline.Draft(NotificationType.TASK_ASSIGNED, "task", w.taskId(), w.caseId(),
                "Task assigned to you: Chase", "body", "/t/" + "x" + "/path", Tone.INFO, dedupe);
    }

    private int deliver(World w, List<UUID> to, String dedupe) {
        return deliver(w, to, dedupe, new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, w.taskId()));
    }

    private int deliver(World w, List<UUID> to, String dedupe, NotificationPipeline.Visibility visibility) {
        var written = new int[1];
        fixture.runAsUser(w.tenant(), w.actor(), () -> written[0] = pipeline.deliver(draft(w, dedupe), to, w.actor(),
                visibility));
        return written[0];
    }

    private long sentAudits(UUID tenant) {
        return ownerJdbc().queryForObject(
                "select count(*) from audit_event where tenant_id = ? and action = 'notification.sent'", Long.class, tenant);
    }

    @Test
    void aVisibleRecipientGetsOneRowAndOneQueuedEmail() {
        var w = world("pipe-ok");
        assertThat(deliver(w, List.of(w.viewer()), null)).isEqualTo(1);
        var n = support.notifications(w.tenant()).get(0);
        assertThat(n.get("recipient_user_id")).isEqualTo(w.viewer());
        assertThat(n.get("in_app")).isEqualTo(true);
        assertThat(n.get("email_state")).isEqualTo("QUEUED");
        assertThat(n.get("subject_type")).isEqualTo("task");
        assertThat(n.get("subject_id")).isEqualTo(w.taskId());
        assertThat(n.get("case_id")).isEqualTo(w.caseId());
        var outbox = support.outbox(w.tenant());
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).get("notification_id")).isEqualTo(n.get("id"));
        assertThat(outbox.get(0).get("to_address")).isEqualTo("viewer@pipe-ok.test");
        assertThat(sentAudits(w.tenant())).isEqualTo(1L);
    }

    @Test
    void theActorIsNeverNotified() {   // Review Focus 2
        var w = world("pipe-actor");
        assertThat(deliver(w, List.of(w.actor()), null)).isZero();
        assertThat(support.notifications(w.tenant())).isEmpty();
    }

    @Test
    void aRecipientWhoCannotViewTheSubjectGetsNothing() {
        var w = world("pipe-blind");
        assertThat(deliver(w, List.of(w.blind()), null)).isZero();
        assertThat(support.notifications(w.tenant())).isEmpty();
        assertThat(support.outbox(w.tenant())).isEmpty();
    }

    @Test
    void inactiveAndPortalRecipientsGetNothing() {
        var w = world("pipe-inactive");
        ownerJdbc().update("update app_user set status = 'DEACTIVATED' where id = ?", w.viewer());
        UUID customerId = ownerJdbc().queryForObject("select customer_id from onboarding_case where id = ?", UUID.class, w.caseId());
        UUID portal = fixture.createPortalUserForContact(w.tenant(), customerId, "portal@pipe-inactive.test");
        assertThat(deliver(w, List.of(w.viewer(), portal), null)).isZero();
        assertThat(support.notifications(w.tenant())).isEmpty();
    }

    @Test
    void duplicateAndNullCandidatesAreDeliveredAtMostOnce() {
        var w = world("pipe-dupes");
        var to = new java.util.ArrayList<UUID>();
        to.add(w.viewer());
        to.add(null);
        to.add(w.viewer());
        assertThat(deliver(w, to, null)).isEqualTo(1);
        assertThat(support.notifications(w.tenant())).hasSize(1);
    }

    @Test
    void preferencesRouteEachChannel() {
        var w = world("pipe-prefs");
        ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'TASK_ASSIGNED', false, true, now(), now())", w.tenant(), w.viewer());
        deliver(w, List.of(w.viewer()), null);
        var n = support.notifications(w.tenant()).get(0);
        assertThat(n.get("in_app")).isEqualTo(false);
        assertThat(n.get("email_state")).isEqualTo("QUEUED");
    }

    @Test
    void anInAppOnlyPreferenceWritesARowAndNoEmail() {
        var w = world("pipe-inapp");
        ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'TASK_ASSIGNED', true, false, now(), now())", w.tenant(), w.viewer());
        assertThat(deliver(w, List.of(w.viewer()), null)).isEqualTo(1);
        var n = support.notifications(w.tenant()).get(0);
        assertThat(n.get("in_app")).isEqualTo(true);
        assertThat(n.get("email_state")).isEqualTo("NONE");
        assertThat(support.outbox(w.tenant())).isEmpty();
    }

    @Test
    void bothChannelsOffWritesNothing() {
        var w = world("pipe-off");
        ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'TASK_ASSIGNED', false, false, now(), now())", w.tenant(), w.viewer());
        assertThat(deliver(w, List.of(w.viewer()), null)).isZero();
        assertThat(support.notifications(w.tenant())).isEmpty();
        assertThat(support.outbox(w.tenant())).isEmpty();
    }

    @Test
    void aDigestUserIsMarkedPendingAndNotQueued() {
        var w = world("pipe-digest");
        ownerJdbc().update("insert into notification_settings (id, tenant_id, user_id, email_cadence, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'DAILY', now(), now())", w.tenant(), w.viewer());
        deliver(w, List.of(w.viewer()), null);
        assertThat(support.notifications(w.tenant()).get(0).get("email_state")).isEqualTo("DIGEST_PENDING");
        assertThat(support.outbox(w.tenant())).isEmpty();
    }

    @Test
    void aDedupeKeyDeliversOnce() {
        var w = world("pipe-dedupe");
        assertThat(deliver(w, List.of(w.viewer()), "K1")).isEqualTo(1);
        assertThat(deliver(w, List.of(w.viewer()), "K1")).isZero();
        assertThat(support.notifications(w.tenant())).hasSize(1);
        assertThat(support.outbox(w.tenant())).hasSize(1);
        assertThat(sentAudits(w.tenant())).isEqualTo(1L);
    }

    @Test
    void anEscalationDraftIsRefused() {
        var w = world("pipe-esc");
        var esc = new NotificationPipeline.Draft(NotificationType.ESCALATION, "case", w.caseId(), w.caseId(),
                "t", "b", "/p", Tone.RISK, null);
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.actor(), () -> pipeline.deliver(esc,
                List.of(w.viewer()), w.actor(), new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, w.caseId()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(support.notifications(w.tenant())).isEmpty();
    }

    @Test
    void aRolledBackActionLeavesNoNotificationAndNoOutboxRow() {   // invariant 3
        var w = world("pipe-rollback");
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.actor(), () -> {
            pipeline.deliver(draft(w, null), List.of(w.viewer()), w.actor(),
                    new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, w.taskId()));
            throw new IllegalStateException("the action failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(support.notifications(w.tenant())).isEmpty();
        assertThat(support.outbox(w.tenant())).isEmpty();
        assertThat(sentAudits(w.tenant())).isZero();
    }

    @Test
    void aConsumedMarkerIsInvisibleUnsentAndUnaudited() {
        var w = world("pipe-marker");
        fixture.runAs(w.tenant(), () -> pipeline.consume(draft(w, "M1"), w.viewer()));
        var n = support.notifications(w.tenant()).get(0);
        assertThat(n.get("in_app")).isEqualTo(false);
        assertThat(n.get("email_state")).isEqualTo("NONE");
        assertThat(support.outbox(w.tenant())).isEmpty();
        assertThat(sentAudits(w.tenant())).isZero();
        assertThat(deliver(w, List.of(w.viewer()), "M1")).as("the marker consumed the key").isZero();
    }

    @Test
    void aMarkerNeedsADedupeKey() {
        var w = world("pipe-marker-nokey");
        assertThatThrownBy(() -> fixture.runAs(w.tenant(), () -> pipeline.consume(draft(w, null), w.viewer())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A DEPARTMENT-scoped task.view holder is gated by the case's owning department -- the
     * recipient's own scope, evaluated through the Task descriptor, not a blanket ALL.
     */
    @Test
    void aDepartmentScopedRecipientIsGatedByTheirOwnDepartment() {
        var w = world("pipe-dept");
        UUID[] ids = new UUID[3];
        fixture.runAs(w.tenant(), () -> {
            ids[2] = fixture.createDepartment(w.tenant(), "Ops");
            UUID other = fixture.createDepartment(w.tenant(), "Legal");
            ids[0] = fixture.createUserInDepartment(w.tenant(), "in@pipe-dept.test", ids[2]);
            ids[1] = fixture.createUserInDepartment(w.tenant(), "out@pipe-dept.test", other);
        });
        ownerJdbc().update("update onboarding_case set owning_department_id = ? where id = ?", ids[2], w.caseId());
        for (UUID u : List.of(ids[0], ids[1])) grant(w.tenant(), u, Map.of(PermissionKeys.TASK_VIEW, Scope.DEPARTMENT));

        assertThat(deliver(w, List.of(ids[0], ids[1]), null)).isEqualTo(1);
        var rows = support.notifications(w.tenant());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(ids[0]);
    }

    /**
     * Every entity type a later producer names in a Visibility (Task, Case, Customer, Document,
     * Agreement) resolves for a narrower-than-ALL recipient without throwing: an unregistered
     * descriptor would raise inside the producer's MANDATORY transaction and roll its action back.
     */
    @Test
    void everyVisibilityEntityTypeResolvesForADepartmentScopedRecipient() {
        var w = world("pipe-types");
        UUID customerId = ownerJdbc().queryForObject("select customer_id from onboarding_case where id = ?", UUID.class, w.caseId());
        UUID[] recipient = new UUID[2];
        fixture.runAs(w.tenant(), () -> {
            recipient[1] = fixture.createDepartment(w.tenant(), "Ops");
            recipient[0] = fixture.createUserInDepartment(w.tenant(), "dept@pipe-types.test", recipient[1]);
        });
        ownerJdbc().update("update onboarding_case set owning_department_id = ? where id = ?", recipient[1], w.caseId());
        ownerJdbc().update("update customer set owning_department_id = ? where id = ?", recipient[1], customerId);
        grant(w.tenant(), recipient[0], Map.of(
                PermissionKeys.TASK_VIEW, Scope.DEPARTMENT, PermissionKeys.CASE_VIEW, Scope.DEPARTMENT,
                PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT, PermissionKeys.DOCUMENT_VIEW, Scope.DEPARTMENT,
                PermissionKeys.AGREEMENT_VIEW, Scope.DEPARTMENT));
        List<UUID> to = List.of(recipient[0]);

        assertThat(deliver(w, to, null, new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, w.taskId())))
                .as("task in the recipient's department").isEqualTo(1);
        assertThat(deliver(w, to, null, new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, w.caseId())))
                .as("case in the recipient's department").isEqualTo(1);
        assertThat(deliver(w, to, null, new NotificationPipeline.Visibility(PermissionKeys.CUSTOMER_VIEW, Customer.class, customerId)))
                .as("customer in the recipient's department").isEqualTo(1);
        assertThat(deliver(w, to, null, new NotificationPipeline.Visibility(PermissionKeys.DOCUMENT_VIEW, Document.class, Uuid7.generate())))
                .as("an unknown document resolves to nothing, not an exception").isZero();
        assertThat(deliver(w, to, null, new NotificationPipeline.Visibility(PermissionKeys.AGREEMENT_VIEW, Agreement.class, Uuid7.generate())))
                .as("an unknown agreement resolves to nothing, not an exception").isZero();
        assertThat(support.notifications(w.tenant())).hasSize(3);
    }

    @Test
    void theGatedConsumeWritesAMarkerOnlyForARecipientWhoCanView() {
        var w = world("pipe-consume");
        var visibility = new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, w.taskId());
        fixture.runAsUser(w.tenant(), w.actor(), () -> {
            pipeline.consume(draft(w, "K:1"), w.blind(), visibility);
            pipeline.consume(draft(w, "K:2"), w.viewer(), visibility);
        });
        var rows = support.notifications(w.tenant());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(w.viewer());
        assertThat(rows.get(0).get("in_app")).isEqualTo(false);
        assertThat(rows.get(0).get("dedupe_key")).isEqualTo("K:2");
    }
}
