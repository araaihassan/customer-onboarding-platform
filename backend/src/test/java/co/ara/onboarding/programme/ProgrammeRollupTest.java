package co.ara.onboarding.programme;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.MilestoneStatus;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14: the duration-weighted rollup ({@link ProgrammeRollup},
 * {@link co.ara.onboarding.journey.CaseWeightReader}) and the invariant it
 * exists to hold -- design spec §6.3's non-negotiable, restated for an
 * AGGREGATE rather than a list: a programme's rolled-up progress must never be
 * computed over a journey the current reader could not otherwise open, even
 * without exposing that journey as its own row. The identical shape CLAUDE.md
 * records for the {@code taskSummary} gap sub-project 3's own Phase 1 closed.
 *
 * Every scenario builds its own tenant/programme/journeys rather than sharing
 * one {@code @BeforeEach} fixture -- the three read tests need different actor
 * and visibility shapes, and a shared setup would either force irrelevant
 * detail into each test or silently couple them.
 */
class ProgrammeRollupTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired ProgrammeService programmeService;
    @Autowired ProgrammeMembershipService membershipService;
    @Autowired RoleService roles;
    @Autowired CaseRepository caseRepository;
    @Autowired MilestoneRepository milestoneRepository;
    @Autowired MilestoneDefinitionRepository milestoneDefinitionRepository;
    @Autowired StageRepository stageRepository;

    @Test
    void progressIsWeightedByEachJourneysTotalEstimatedDuration() {
        UUID tenant = fixture.createTenant("prog-rollup-weighted-" + Uuid7.generate());
        var programmeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            programmeId[0] = programmeService.create(new CreateProgrammeRequest(
                    "Weighted Programme", customerId, null, null, null, null)).id();

            // 10-day journey at 100%, 30-day journey at 0% -> weighted 25%, not
            // the unweighted 50% a plain average of the two percentages alone
            // would give.
            seedJourneyInProgramme(tenant, programmeId[0], 10, 100);
            seedJourneyInProgramme(tenant, programmeId[0], 30, 0);
        });

        var viewRef = new ProgrammeDetailView[1];
        fixture.runAs(tenant, () -> viewRef[0] = programmeService.get(programmeId[0]));

        assertThat(viewRef[0].rolledUpProgressPercent()).isEqualTo(25);
        assertThat(viewRef[0].journeysCovered()).isEqualTo(2);
    }

    @Test
    void theRollupCoversOnlyTheJourneysTheReaderCanSeeAndSaysHowMany() {
        // Computing over journeys the viewer cannot open would be an
        // aggregate-only leak -- the identical shape Phase 1 Task 5 just
        // closed for taskSummary.
        UUID tenant = fixture.createTenant("prog-rollup-visible-" + Uuid7.generate());
        var sponsor = new UUID[1];
        var programmeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            sponsor[0] = fixture.createUser(tenant, "sponsor+" + Uuid7.generate() + "@example.com");
            // ASSIGNED only for programme.view/case.view, no DEPARTMENT/TEAM/ALL
            // grant to fall back on -- a passing test proves the
            // participation-mediated read specifically, not a wider one
            // masking it. customer.view and workflow.view are granted ALL:
            // ProgrammeService.get's own customer.view cross-permission
            // dependency (resolving customerName), and CaseWeightReader's own
            // workflow.view dependency (resolving milestone_definition
            // durations) -- the identical case.view/workflow.view coupling
            // CLAUDE.md documents for CaseService's currentStageName. Neither
            // is what this test is actually proving.
            grant(sponsor[0], Map.of(
                    PermissionKeys.PROGRAMME_VIEW, Scope.ASSIGNED,
                    PermissionKeys.CASE_VIEW, Scope.ASSIGNED,
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            programmeId[0] = programmeService.create(new CreateProgrammeRequest(
                    "Sponsor's Programme", customerId, null, null, null, null)).id();
            // A genuine programme_participant row -- PROGRAMME_VIEW ASSIGNED
            // resolves through participation in THIS programme, same as
            // ProgrammeScopeTest; without it the sponsor's own case_participant
            // row below grants nothing, since the programme itself 404s first.
            membershipService.addParticipant(programmeId[0],
                    new AddProgrammeParticipantRequest(sponsor[0], RelationshipType.PARTICIPANT, false));

            // visible: sponsor holds a genuine case_participant row, and this
            // journey alone is 100% done with a positive weight -- the
            // expected rollup (100%) is unambiguous.
            UUID visible = seedJourneyInProgramme(tenant, programmeId[0], 5, 100);
            journey.addParticipant(tenant, visible, sponsor[0],
                    RelationshipType.PARTICIPANT, ParticipantStatus.ACTIVE);

            // invisible: sponsor holds NOTHING on this case. Its progress
            // (0%) and weight (20 days, four times the visible journey's) are
            // chosen so that a leak pulling it into the rollup would produce a
            // clearly wrong, easy-to-notice number (20%, not 100%) rather than
            // one that could pass by coincidence.
            seedJourneyInProgramme(tenant, programmeId[0], 20, 0);
        });

        var viewRef = new ProgrammeDetailView[1];
        fixture.runAsUser(tenant, sponsor[0], () -> viewRef[0] = programmeService.get(programmeId[0]));

        assertThat(viewRef[0].journeysCovered()).isEqualTo(1);
        assertThat(viewRef[0].rolledUpProgressPercent()).isEqualTo(100);
    }

    @Test
    void aProgrammeWithNoVisibleJourneysReportsZeroPercentOverZeroJourneys() {
        // Not a divide-by-zero, and not "100% complete". The stranger holds
        // programme.view (through participation, ASSIGNED) but deliberately
        // NO case.view grant at all, proving CaseWeightReader is never even
        // invoked (and so never throws) when journeysFor already found
        // nothing visible.
        UUID tenant = fixture.createTenant("prog-rollup-none-visible-" + Uuid7.generate());
        var stranger = new UUID[1];
        var programmeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            stranger[0] = fixture.createUser(tenant, "stranger+" + Uuid7.generate() + "@example.com");
            grant(stranger[0], Map.of(
                    PermissionKeys.PROGRAMME_VIEW, Scope.ASSIGNED,
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            programmeId[0] = programmeService.create(new CreateProgrammeRequest(
                    "Stranger's Programme", customerId, null, null, null, null)).id();
            membershipService.addParticipant(programmeId[0],
                    new AddProgrammeParticipantRequest(stranger[0], RelationshipType.PARTICIPANT, false));

            seedJourneyInProgramme(tenant, programmeId[0], 10, 50);
        });

        var viewRef = new ProgrammeDetailView[1];
        fixture.runAsUser(tenant, stranger[0], () -> viewRef[0] = programmeService.get(programmeId[0]));

        assertThat(viewRef[0].journeysCovered()).isZero();
        assertThat(viewRef[0].rolledUpProgressPercent()).isZero();
    }

    @Test
    void noProgrammeCodePathEverCallsTheEngine() {
        JavaClasses programmeClasses = classesIn("co.ara.onboarding.programme");
        assertThat(programmeClasses)
                .noneMatch(c -> dependsOn(c, "co.ara.onboarding.journey.CaseEngine"));
    }

    /**
     * A journey linked to the programme with a total milestone weight of
     * exactly {@code durationDays} and a fixed {@code progressPercent}, set
     * directly on the {@code Case} row rather than driven through
     * {@code CaseEngine} -- these tests are about the rollup's own weighting
     * arithmetic and visibility filtering, not the engine's progress
     * computation.
     */
    private UUID seedJourneyInProgramme(UUID tenant, UUID programmeId, int durationDays, int progressPercent) {
        Case c = journey.newCase(tenant);

        Stage stage = new Stage();
        stage.setId(Uuid7.generate());
        stage.setTenantId(tenant);
        stage.setVersionId(c.getVersionId());
        stage.setOrdinal(1);
        stage.setName("Rollup Fixture Stage " + Uuid7.generate());
        stageRepository.saveAndFlush(stage);

        MilestoneDefinition definition = new MilestoneDefinition();
        definition.setId(Uuid7.generate());
        definition.setTenantId(tenant);
        definition.setVersionId(c.getVersionId());
        definition.setStageId(stage.getId());
        definition.setOrdinal(1);
        definition.setName("Rollup Fixture Milestone " + Uuid7.generate());
        definition.setEstimatedDurationDays(durationDays);
        milestoneDefinitionRepository.saveAndFlush(definition);

        Milestone m = new Milestone();
        m.setId(Uuid7.generate());
        m.setTenantId(tenant);
        m.setCaseId(c.getId());
        m.setMilestoneDefinitionId(definition.getId());
        m.setStatus(MilestoneStatus.PENDING);
        milestoneRepository.saveAndFlush(m);

        c.setProgressPercent(progressPercent);
        caseRepository.saveAndFlush(c);

        membershipService.addJourney(programmeId, new AddJourneyRequest(c.getId()));
        return c.getId();
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private static JavaClasses classesIn(String pkg) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(pkg);
    }

    private static boolean dependsOn(com.tngtech.archunit.core.domain.JavaClass c, String fullyQualifiedName) {
        return c.getDirectDependenciesFromSelf().stream()
                .anyMatch(dep -> dep.getTargetClass().getFullName().equals(fullyQualifiedName));
    }
}
