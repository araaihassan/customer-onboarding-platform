package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AttributeType;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.AttributeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired MigrationService migrations;
    @Autowired RoleService roles;
    @Autowired BusinessCalendar calendar;
    @Autowired AuditEventRepository auditEvents;

    /** The panel's two numbers: "31 cases on v4 / 18 eligible to migrate". */
    @Test
    void thePreviewCountsCasesOnTheOldVersionAndThoseEligible() {
        UUID tenant = fixture.createTenant("migration-preview");
        fixture.runAs(tenant, () -> {
            UUID v1 = simpleVersion();
            UUID templateId = journey.templateOf(v1);
            for (int i = 0; i < 3; i++) {
                aCaseOn(tenant, templateId);
            }
            UUID v2 = journey.publishNewVersion(templateId, simpleWorkflowRequest());

            MigrationPreviewView preview = migrations.preview(v2);
            assertThat(preview.onVersion()).isEqualTo(3);
            assertThat(preview.eligible()).isEqualTo(3);
            assertThat(preview.candidates()).hasSize(3);
            assertThat(preview.candidates()).allSatisfy(c -> assertThat(c.eligible()).isTrue());
        });
    }

    @Test
    void aCaseWhoseStagesAllStillExistIsEligible() {
        UUID tenant = fixture.createTenant("migration-eligible");
        fixture.runAs(tenant, () -> {
            UUID v1 = simpleVersion();
            UUID templateId = journey.templateOf(v1);
            UUID caseId = aCaseOn(tenant, templateId);
            UUID v2 = journey.publishNewVersion(templateId, simpleWorkflowRequest());

            MigrationPreviewView preview = migrations.preview(v2);
            assertThat(preview.candidates()).singleElement().satisfies(c -> {
                assertThat(c.caseId()).isEqualTo(caseId);
                assertThat(c.eligible()).isTrue();
                assertThat(c.reason()).isNull();
            });
        });
    }

    /**
     * Reason one: the new version deleted a stage this case has already passed.
     * Migrating would leave completed work pointing at definitions that no longer
     * exist.
     */
    @Test
    void aCaseThatHasPassedADeletedStageIsIneligibleWithThatReason() {
        UUID tenant = fixture.createTenant("migration-deleted-stage");
        fixture.runAs(tenant, () -> {
            UUID v1 = journey.publish(twoStageRequest());
            UUID templateId = journey.templateOf(v1);
            UUID caseId = aCaseOn(tenant, templateId);

            // Passes "Legal Review" (stage one): its only requirement satisfied, so
            // the case auto-advances into "Final" (stage two), which becomes current.
            requirements.satisfy(firstRequirementOfStage(caseId, 0), null, null);
            assertThat(cases.roadmap(caseId).stages().get(1).milestones()).isNotEmpty();   // sanity: Final exists

            // v2 keeps only "Final".
            UUID v2 = journey.publishNewVersion(templateId, new WorkflowDefinitionRequest(
                    List.of(stage("final", "Final", List.of(
                            milestone("final-m", "Final Milestone", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));

            MigrationPreviewView preview = migrations.preview(v2);
            CandidateView candidate = preview.candidates().get(0);
            assertThat(candidate.eligible()).isFalse();
            assertThat(candidate.reason()).contains("Legal Review");
        });
    }

    /** Reason two: a newly required attribute the case has no value for. */
    @Test
    void aCaseMissingANewlyRequiredAttributeIsIneligible() {
        UUID tenant = fixture.createTenant("migration-attribute");
        fixture.runAs(tenant, () -> {
            UUID v1 = simpleVersion();
            UUID templateId = journey.templateOf(v1);
            UUID caseId = aCaseOn(tenant, templateId);

            UUID v2 = journey.publishNewVersion(templateId, new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(new AttributeRequest("tier", "Tier", AttributeType.STRING, true, null)),
                    0L));

            MigrationPreviewView preview = migrations.preview(v2);
            CandidateView candidate = preview.candidates().stream()
                    .filter(c -> c.caseId().equals(caseId)).findFirst().orElseThrow();
            assertThat(candidate.eligible()).isFalse();
            assertThat(candidate.reason()).contains("tier");
        });
    }

    @Test
    void aCompletedCaseIsNotACandidate() {
        UUID tenant = fixture.createTenant("migration-completed");
        fixture.runAs(tenant, () -> {
            UUID v1 = simpleVersion();
            UUID templateId = journey.templateOf(v1);
            UUID caseId = aCaseOn(tenant, templateId);
            requirements.satisfy(firstRequirementOfStage(caseId, 0), null, null);
            assertThat(cases.get(caseId).status()).isEqualTo(CaseStatus.COMPLETED);

            UUID v2 = journey.publishNewVersion(templateId, simpleWorkflowRequest());

            MigrationPreviewView preview = migrations.preview(v2);
            assertThat(preview.onVersion()).isZero();
            assertThat(preview.candidates()).isEmpty();
        });
    }

    @Test
    void migratingRepinsTheCaseAndReinstantiatesFutureStages() {
        UUID tenant = fixture.createTenant("migration-repin");
        fixture.runAs(tenant, () -> {
            UUID v1 = journey.publish(new WorkflowDefinitionRequest(List.of(
                    stage("intake", "Intake", List.of(
                            milestone("intake-m", "Intake Milestone", 1, List.of(), List.of(manual("Do it"))))),
                    stage("processing", "Processing", List.of(
                            milestone("verify", "Verify", 1, List.of(), List.of(manual("Do it"))),
                            milestone("approve", "Approve", 1, List.of(), List.of(manual("Do it"))))),
                    stage("closeout", "Closeout", List.of(
                            milestone("close", "Close", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));
            UUID templateId = journey.templateOf(v1);
            UUID caseId = aCaseOn(tenant, templateId);

            requirements.satisfy(firstRequirementOfMilestone(caseId, 0), null, null);   // Intake -> DONE
            requirements.satisfy(firstRequirementOfMilestone(caseId, 1), null, null);   // Verify -> DONE

            // v2: same three stages, plus a fourth appended.
            UUID v2 = journey.publishNewVersion(templateId, new WorkflowDefinitionRequest(List.of(
                    stage("intake", "Intake", List.of(
                            milestone("intake-m", "Intake Milestone", 1, List.of(), List.of(manual("Do it"))))),
                    stage("processing", "Processing", List.of(
                            milestone("verify", "Verify", 1, List.of(), List.of(manual("Do it"))),
                            milestone("approve", "Approve", 1, List.of(), List.of(manual("Do it"))))),
                    stage("closeout", "Closeout", List.of(
                            milestone("close", "Close", 1, List.of(), List.of(manual("Do it"))))),
                    stage("wrapup", "Wrapup", List.of(
                            milestone("wrap", "Wrap", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));

            int migrated = migrations.migrate(v2, List.of(caseId));

            assertThat(migrated).isEqualTo(1);
            assertThat(cases.get(caseId).versionId()).isEqualTo(v2);
            assertThat(milestoneOrdinal(caseId, 1).status()).isEqualTo(MilestoneStatus.DONE);
            assertThat(cases.roadmap(caseId).stages()).hasSize(4);
        });
    }

    @Test
    void migratingRecomputesDatesAndProgressAgainstTheNewDurations() {
        UUID tenant = fixture.createTenant("migration-dates");
        fixture.runAs(tenant, () -> {
            UUID v1 = journey.publish(new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 2, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));
            UUID templateId = journey.templateOf(v1);
            UUID caseId = aCaseOn(tenant, templateId);

            UUID v2 = journey.publishNewVersion(templateId, new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 5, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));

            migrations.migrate(v2, List.of(caseId));

            LocalDate expectedDueDate = calendar.plusBusinessDays(LocalDate.now(clock), 5);
            assertThat(milestoneOrdinal(caseId, 0).dueDate()).isEqualTo(expectedDueDate);
            assertThat(cases.get(caseId).targetCompletionDate()).isEqualTo(expectedDueDate);
        });
    }

    @Test
    void migratingAnIneligibleCaseIsRefusedRatherThanSkippedSilently() {
        UUID tenant = fixture.createTenant("migration-refused");
        var caseId = new UUID[1];
        var v2Box = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID v1 = journey.publish(twoStageRequest());
            UUID templateId = journey.templateOf(v1);
            caseId[0] = aCaseOn(tenant, templateId);
            requirements.satisfy(firstRequirementOfStage(caseId[0], 0), null, null);   // passes "Legal Review"

            v2Box[0] = journey.publishNewVersion(templateId, new WorkflowDefinitionRequest(
                    List.of(stage("final", "Final", List.of(
                            milestone("final-m", "Final Milestone", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));
        });

        // Never assert inside the runAs lambda -- see CaseCreationTest's own note.
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> migrations.migrate(v2Box[0], List.of(caseId[0]))))
                .isInstanceOf(CaseNotMigratableException.class)
                .hasMessageContaining("Legal Review");
    }

    @Test
    void migrationRequiresCaseMigrate() {
        UUID tenant = fixture.createTenant("migration-permission");
        var pm = new UUID[1];
        var v2Box = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID v1 = simpleVersion();
            UUID templateId = journey.templateOf(v1);
            aCaseOn(tenant, templateId);
            v2Box[0] = journey.publishNewVersion(templateId, simpleWorkflowRequest());

            pm[0] = fixture.createUser(tenant, "pm@example.com");
            UUID role = roles.createRole("Fixture PM " + Uuid7.generate(), "",
                    Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
            roles.assignRole(pm[0], role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, pm[0], () -> migrations.preview(v2Box[0])))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void eachMigratedCaseIsAuditedIndividually() {
        UUID tenant = fixture.createTenant("migration-audit");
        fixture.runAs(tenant, () -> {
            UUID v1 = simpleVersion();
            UUID templateId = journey.templateOf(v1);
            UUID caseA = aCaseOn(tenant, templateId);
            UUID caseB = aCaseOn(tenant, templateId);
            UUID v2 = journey.publishNewVersion(templateId, simpleWorkflowRequest());

            migrations.migrate(v2, List.of(caseA, caseB));

            long migratedEvents = auditEvents.findAll().stream()
                    .filter(e -> e.getAction().equals(AuditActions.CASE_MIGRATED.key()))
                    .filter(e -> e.getResourceId().equals(caseA) || e.getResourceId().equals(caseB))
                    .count();
            assertThat(migratedEvents).isEqualTo(2);
        });
    }

    private UUID simpleVersion() {
        return journey.publish(simpleWorkflowRequest());
    }

    private WorkflowDefinitionRequest simpleWorkflowRequest() {
        return new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))))),
                List.of(), 0L);
    }

    private WorkflowDefinitionRequest twoStageRequest() {
        return new WorkflowDefinitionRequest(List.of(
                stage("legal", "Legal Review", List.of(
                        milestone("legal-m", "Legal Milestone", 1, List.of(), List.of(manual("Do it"))))),
                stage("final", "Final", List.of(
                        milestone("final-m", "Final Milestone", 1, List.of(), List.of(manual("Do it")))))),
                List.of(), 0L);
    }

    private UUID aCaseOn(UUID tenant, UUID templateId) {
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();
    }

    private UUID firstRequirementOfStage(UUID caseId, int stageIndex) {
        return cases.roadmap(caseId).stages().get(stageIndex).milestones().get(0).requirements().get(0).id();
    }

    private UUID firstRequirementOfMilestone(UUID caseId, int flatMilestoneIndex) {
        return milestoneOrdinal(caseId, flatMilestoneIndex).requirements().get(0).id();
    }

    private MilestoneRoadmapView milestoneOrdinal(UUID caseId, int flatIndex) {
        return cases.roadmap(caseId).stages().stream()
                .flatMap(s -> s.milestones().stream())
                .toList().get(flatIndex);
    }
}
