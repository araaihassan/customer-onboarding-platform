package co.ara.onboarding.programme;

import co.ara.onboarding.audit.AuditEvent;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * This sub-project's most important test (design spec §6.3, §9.2, Q20's
 * non-negotiable): a programme's participant list must NEVER be a backdoor to
 * journey access. Programme participation grants read of the programme container
 * alone; a journey inside it is visible to a participant ONLY if they also hold a
 * real {@code CaseParticipant} row on that specific journey, resolved through
 * journey's own existing gated participant API -- never by reaching into
 * {@code CaseParticipantRepository} directly from {@code programme}.
 *
 * Every test shares one seeded scenario: a programme holding two journeys,
 * {@code visible} (sponsor already holds a real case_participant row there) and
 * {@code invisible} (sponsor holds nothing there) -- so a passing
 * {@code aProgrammeParticipantSeesOnlyTheJourneysTheyCouldOtherwiseOpen} proves
 * the filter is genuinely selective, not vacuously true because every journey
 * happens to be visible.
 */
class ProgrammeScopeTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired ProgrammeService programmeService;
    @Autowired ProgrammeMembershipService membershipService;
    @Autowired CaseService caseService;
    @Autowired RoleService roles;
    @Autowired AuditEventRepository auditEvents;

    private UUID tenant;
    private UUID sponsor;
    private UUID visible;
    private UUID invisible;
    private UUID programmeId;

    @BeforeEach
    void seedProgrammeWithATwoJourneyMixOfVisibleAndInvisible() {
        tenant = fixture.createTenant("prog-scope-" + Uuid7.generate());

        fixture.runAs(tenant, () -> {
            sponsor = fixture.createUser(tenant, "sponsor+" + Uuid7.generate() + "@example.com");
            // programme.view/case.view at ASSIGNED ONLY -- no DEPARTMENT/TEAM/ALL
            // grant to fall back on, so a passing test proves the
            // participation-mediated read specifically, not a wider one masking
            // it. customer.view ALL alongside them: ProgrammeService.get's own
            // doc comment names this cross-permission dependency (resolving
            // customerName needs customer.view over the programme's own
            // customer) -- unrelated to what this test actually proves.
            grant(sponsor, Map.of(
                    PermissionKeys.PROGRAMME_VIEW, Scope.ASSIGNED,
                    PermissionKeys.CASE_VIEW, Scope.ASSIGNED,
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            programmeId = programmeService.create(new CreateProgrammeRequest(
                    "Sponsor's Programme", customerId, null, null, null, null)).id();

            // visible: sponsor holds a real case_participant row (PARTICIPANT,
            // one of CaseDescriptor's own assignedRelationships) -- a genuine,
            // independent claim to case.view at ASSIGNED, not manufactured by
            // programme membership.
            visible = journey.newCase(tenant).getId();
            journey.addParticipant(tenant, visible, sponsor, RelationshipType.PARTICIPANT, ParticipantStatus.ACTIVE);

            // invisible: sponsor holds NOTHING on this case.
            invisible = journey.newCase(tenant).getId();

            membershipService.addJourney(programmeId, new AddJourneyRequest(visible));
            membershipService.addJourney(programmeId, new AddJourneyRequest(invisible));
        });
    }

    @Test
    void aProgrammeParticipantSeesOnlyTheJourneysTheyCouldOtherwiseOpen() {
        fixture.runAs(tenant, () -> membershipService.addParticipant(programmeId,
                new AddProgrammeParticipantRequest(sponsor, RelationshipType.PARTICIPANT, false)));

        var viewRef = new ProgrammeDetailView[1];
        fixture.runAsUser(tenant, sponsor, () -> viewRef[0] = programmeService.get(programmeId));

        // The container must not be a backdoor. Programme participation grants
        // read of the PROGRAMME; journey access comes only from a real
        // case_participant row.
        assertThat(viewRef[0].journeys()).extracting(ProgrammeJourneyView::caseId)
                .containsExactly(visible);
    }

    @Test
    void theInvisibleJourneyIs404NotAnEmptyFieldWhenOpenedDirectly() {
        // Not this task's own code (caseService.get predates it) -- included to
        // make the contrast explicit: the same journey that quietly disappears
        // from the programme's list is a real 404 when opened directly, never a
        // blank or partial representation.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, sponsor, () -> caseService.get(invisible)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void grantingJourneyAccessIsAnExplicitWriteThatShowsUpInTheAuditTrail() {
        // APPROVER, not PARTICIPANT: sponsor already holds a real PARTICIPANT
        // case_participant row on `visible` (seeded above), and case_participant
        // carries a genuine UNIQUE(case_id, user_id, relationship) constraint --
        // a real one, not a test artifact, since the same relationship granted
        // twice IS the same fact recorded twice. A different relationship here
        // proves the grant is written independently for BOTH journeys (visible
        // AND invisible), which is the point of this test, without colliding
        // with the pre-existing personal relationship this fixture already gave
        // the sponsor on `visible`.
        fixture.runAs(tenant, () -> membershipService.addParticipant(programmeId,
                new AddProgrammeParticipantRequest(sponsor, RelationshipType.APPROVER, true)));

        var viewRef = new ProgrammeDetailView[1];
        fixture.runAsUser(tenant, sponsor, () -> viewRef[0] = programmeService.get(programmeId));

        assertThat(viewRef[0].journeys()).hasSize(2);

        // A REAL case.participant_added row, written by journey's own
        // CaseService#addParticipant against "onboarding_case"/invisible -- not
        // a programme-side action pretending to be it. Read inside runAs: the
        // query must be tenant-bound for RLS to return anything at all.
        AtomicReference<List<String>> actions = new AtomicReference<>();
        fixture.runAs(tenant, () -> actions.set(auditActionsFor("onboarding_case", invisible)));
        assertThat(actions.get()).contains("case.participant_added");
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private List<String> auditActionsFor(String resourceType, UUID resourceId) {
        return auditEvents.findAll().stream()
                .filter(e -> resourceType.equals(e.getResourceType()) && resourceId.equals(e.getResourceId()))
                .map(AuditEvent::getAction)
                .toList();
    }
}
