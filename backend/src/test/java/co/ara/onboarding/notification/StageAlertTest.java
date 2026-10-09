package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.PublishValidationException;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.MilestoneRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.StageRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 6B spec 5.5 / 8: stage entered/exited alerts from tenant templates, and the publish rule. */
class StageAlertTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired NotificationTestSupport support;

    private static final Map<String, Scope> COMPLETER = Map.of(
            PermissionKeys.MILESTONE_COMPLETE, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL,
            PermissionKeys.WORKFLOW_VIEW, Scope.ALL);

    private static MilestoneRequest one(String key) {
        return milestone(key, "Milestone " + key, 1, List.of(), List.of(manual("Do " + key)));
    }

    private static StageRequest stage(String key, String name, String templateKey) {
        return new StageRequest(key, name, null, false, true, true, null, WriteScope.ANY, templateKey,
                null, null, List.of(one("m-" + key)), List.of());
    }

    private void insertTemplate(UUID t, String key, String es, String eb, String xs, String xb, boolean active) {
        PostgresTestBase.ownerJdbcForSupport().update("""
                INSERT INTO notification_template (id, tenant_id, key, name, entered_subject, entered_body,
                    exited_subject, exited_body, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())""",
                Uuid7.generate(), t, key, "Template " + key, es, eb, xs, xb, active);
    }

    private record Arranged(UUID t, UUID caseId, UUID owner, UUID actor) {}

    /** A two-stage case (stage one keyed {@code k1}, stage two keyed {@code k2}) owned by a fresh user. */
    private Arranged arrange(String slug, String k1, String k2) {
        UUID t = fixture.createTenant(slug);
        UUID owner = fixture.runAsReturning(t, () -> fixture.createUser(t, "owner@" + slug + ".test"));
        support.grant(t, owner, Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID actor = fixture.runAsReturning(t, () -> fixture.createUser(t, "dave@" + slug + ".test"));
        support.grant(t, actor, COMPLETER);
        return new Arranged(t, null, owner, actor);
    }

    private Arranged open(Arranged a, String k1, String k2) {
        UUID caseId = fixture.runAsReturning(a.t(), () -> {
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", k1), stage("s2", "Stage Two", k2)), List.of(), 0L));
            UUID customer = fixture.createCustomerOwnedBy(a.t(), "Acme", a.owner());
            return cases.create(new CreateCaseRequest(customer, journey.templateOf(versionId), "Kick case", Map.of())).id();
        });
        return new Arranged(a.t(), caseId, a.owner(), a.actor());
    }

    private void completeStageOne(Arranged a) {
        UUID rid = fixture.runAsReturning(a.t(), () -> cases.roadmap(a.caseId()).stages().get(0)
                .milestones().get(0).requirements().get(0).id());
        fixture.runAsUser(a.t(), a.actor(), () -> requirements.satisfy(rid, null, null));
    }

    private List<Map<String, Object>> stageRows(Arranged a) {
        return support.rowsFor(a.t(), a.owner()).stream()
                .filter(r -> "STAGE_CHANGED".equals(r.get("type"))).toList();
    }

    @Test
    void enteringAKeyedStageAlertsTheCaseAudience() {
        var pre = arrange("sa-enter", null, "kickoff");
        insertTemplate(pre.t(), "kickoff", "{case} entered {stage}", "For {customer}, owner {owner}", null, null, true);
        var a = open(pre, null, "kickoff");

        completeStageOne(a);

        var rows = stageRows(a);
        assertThat(rows).hasSize(1);
        var n = rows.get(0);
        assertThat(n.get("title")).isEqualTo("Kick case entered Stage Two");
        assertThat(n.get("body")).isEqualTo("For Acme, owner " + ownerName(a));
        assertThat(n.get("subject_type")).isEqualTo("stage");
        assertThat(n.get("case_id")).isEqualTo(a.caseId());
        assertThat(n.get("tone")).isEqualTo("INFO");
        // Strictly cause before effect: the stage alert is the last notification sent (the milestone one precedes it).
        var causeAt = PostgresTestBase.ownerJdbcForSupport().queryForObject(
                "select max(occurred_at) from audit_event where tenant_id = ? and action = 'case.stage_entered'",
                java.sql.Timestamp.class, a.t());
        var effectAt = PostgresTestBase.ownerJdbcForSupport().queryForObject(
                "select max(occurred_at) from audit_event where tenant_id = ? and action = 'notification.sent'",
                java.sql.Timestamp.class, a.t());
        assertThat(causeAt).isBefore(effectAt);
    }

    private String ownerName(Arranged a) {
        return PostgresTestBase.ownerJdbcForSupport().queryForObject(
                "select full_name from app_user where id = ?", String.class, a.owner());
    }

    @Test
    void exitingAlertsOnlyWhenTheTemplateHasAnExitPair() {
        var pre = arrange("sa-exit", "left", null);
        insertTemplate(pre.t(), "left", "Entered {stage}", "in", "Left {stage}", "Done with {stage} for {customer}", true);
        var a = open(pre, "left", null);
        assertThat(stageRows(a)).extracting(r -> r.get("title")).containsExactly("Entered Stage One");

        completeStageOne(a);

        assertThat(stageRows(a)).extracting(r -> r.get("title")).containsExactly("Entered Stage One", "Left Stage One");

        // An entry-only template sends nothing on exit.
        var pre2 = arrange("sa-exit-none", "entryonly", null);
        insertTemplate(pre2.t(), "entryonly", "Entered {stage}", "in", null, null, true);
        var b = open(pre2, "entryonly", null);
        completeStageOne(b);
        assertThat(stageRows(b)).extracting(r -> r.get("title")).containsExactly("Entered Stage One");
    }

    @Test
    void anUnkeyedStageSendsNothing() {
        var a = open(arrange("sa-unkeyed", null, null), null, null);

        completeStageOne(a);

        assertThat(stageRows(a)).isEmpty();
    }

    @Test
    void aDeactivatedTemplateSendsNothing() {
        var pre = arrange("sa-inactive", null, "kickoff");
        insertTemplate(pre.t(), "kickoff", "{case} entered {stage}", "body", null, null, true);
        var a = open(pre, null, "kickoff");
        PostgresTestBase.ownerJdbcForSupport().update(
                "update notification_template set active = false where tenant_id = ? and key = 'kickoff'", a.t());

        completeStageOne(a);

        assertThat(stageRows(a)).isEmpty();
    }

    @Test
    void publishingAStageThatNamesAMissingTemplateIs422() {
        UUID t = fixture.createTenant("sa-publish");
        var request = new WorkflowDefinitionRequest(List.of(stage("s1", "Stage One", "ghost")), List.of(), 0L);

        assertThatThrownBy(() -> fixture.runAs(t, () -> journey.publish(request)))
                .isInstanceOf(PublishValidationException.class)
                .satisfies(e -> assertThat(((PublishValidationException) e).problems())
                        .anyMatch(p -> p.contains("Stage One") && p.contains("'ghost'")));

        insertTemplate(t, "ghost", "s", "b", null, null, true);
        fixture.runAs(t, () -> journey.publish(request));

        // A template that exists but is inactive still passes: a frozen version cannot be fixed later.
        insertTemplate(t, "dormant", "s", "b", null, null, false);
        var dormant = new WorkflowDefinitionRequest(List.of(stage("s1", "Stage One", "dormant")), List.of(), 0L);
        fixture.runAs(t, () -> journey.publish(dormant));
    }
}
