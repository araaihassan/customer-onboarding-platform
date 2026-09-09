package co.ara.onboarding.programme;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 13 fix round 1 -- three Important findings from the task review, none of
 * them touching the core visibility filter ({@code ProgrammeScopeTest}) or the
 * {@code alsoGrantJourneyAccess} -> {@code CaseService.addParticipant} routing,
 * both confirmed correct by the review.
 */
class ProgrammeMembershipServiceTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired ProgrammeService programmeService;
    @Autowired ProgrammeMembershipService membershipService;
    @Autowired ProgrammeParticipantRepository participantRepository;
    @Autowired ProgrammeCaseRepository programmeCaseRepository;
    @Autowired RoleService roles;

    // ---- Finding 1: no ProgrammeStatus.ACTIVE guard on the four membership mutations ----

    /**
     * The most consequential of the four: without the guard, alsoGrantJourneyAccess=true
     * against an already-deactivated programme would still write real, live
     * CaseParticipant rows through CaseService#addParticipant.
     */
    @Test
    void addParticipantWithJourneyAccessRefusesAnAlreadyDeactivatedProgramme() {
        UUID tenant = fixture.createTenant("prog-membership-inactive-" + Uuid7.generate());
        var sponsor = new UUID[1];
        var programmeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            sponsor[0] = fixture.createUser(tenant, "sponsor+" + Uuid7.generate() + "@example.com");
            programmeId[0] = programmeService.create(new CreateProgrammeRequest(
                    "Programme", customerId, null, null, null, null)).id();
            UUID caseId = journey.newCase(tenant).getId();
            membershipService.addJourney(programmeId[0], new AddJourneyRequest(caseId));
            programmeService.deactivate(programmeId[0]);
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> membershipService.addParticipant(programmeId[0],
                new AddProgrammeParticipantRequest(sponsor[0], RelationshipType.PARTICIPANT, true))))
                .isInstanceOf(ProgrammeNotActiveException.class);
    }

    // ---- Finding 2: re-adding a previously-removed participant/case currently 500s ----

    /**
     * programme_participant_uq is a PLAIN unique index on (programme_id, user_id) --
     * not partial on status -- so before this fix, removeParticipant (status ->
     * REMOVED, row kept) followed by any addParticipant for the same pair always
     * hit that index and raised a raw DataIntegrityViolationException. Fixed to
     * reactivate the existing row instead of inserting a duplicate.
     */
    @Test
    void reAddingARemovedParticipantReactivatesTheExistingRowRatherThan500ing() {
        UUID tenant = fixture.createTenant("prog-reAdd-participant-" + Uuid7.generate());
        var user = new UUID[1];
        var programmeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            user[0] = fixture.createUser(tenant, "member+" + Uuid7.generate() + "@example.com");
            programmeId[0] = programmeService.create(new CreateProgrammeRequest(
                    "Programme", customerId, null, null, null, null)).id();
            membershipService.addParticipant(programmeId[0],
                    new AddProgrammeParticipantRequest(user[0], RelationshipType.PARTICIPANT, false));
            membershipService.removeParticipant(programmeId[0], user[0]);
        });

        // Before the fix this threw DataIntegrityViolationException. After: a clean
        // reactivation, with the relationship type from THIS call, not the original one.
        fixture.runAs(tenant, () -> membershipService.addParticipant(programmeId[0],
                new AddProgrammeParticipantRequest(user[0], RelationshipType.APPROVER, false)));

        fixture.runAs(tenant, () -> {
            List<ProgrammeParticipant> rows = participantRepository.findByProgrammeId(programmeId[0]).stream()
                    .filter(p -> user[0].equals(p.getUserId())).toList();
            // Reactivated in place -- never a second row for the same pair.
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getStatus()).isEqualTo(ProgrammeParticipantStatus.ACTIVE);
            assertThat(rows.get(0).getRelationshipType()).isEqualTo(RelationshipType.APPROVER);
        });
    }

    /**
     * The other half: a genuinely still-ACTIVE participant is a real conflict,
     * not silently reactivated and not a raw 500 either.
     */
    @Test
    void addingAnAlreadyActiveParticipantIsAConflictNotA500() {
        UUID tenant = fixture.createTenant("prog-active-participant-" + Uuid7.generate());
        var user = new UUID[1];
        var programmeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            user[0] = fixture.createUser(tenant, "member+" + Uuid7.generate() + "@example.com");
            programmeId[0] = programmeService.create(new CreateProgrammeRequest(
                    "Programme", customerId, null, null, null, null)).id();
            membershipService.addParticipant(programmeId[0],
                    new AddProgrammeParticipantRequest(user[0], RelationshipType.PARTICIPANT, false));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> membershipService.addParticipant(programmeId[0],
                new AddProgrammeParticipantRequest(user[0], RelationshipType.PARTICIPANT, false))))
                .isInstanceOf(ProgrammeParticipantAlreadyActiveException.class);
    }

    /**
     * programme_case_active_uq is PARTIAL (WHERE removed_at IS NULL), unlike the
     * participant index -- so a case previously removed from a programme carries
     * no collision, and re-linking it (to the SAME programme here) was already a
     * clean insert before this fix. Locked in as a regression test, not a red
     * case: the fix must not turn this into a conflict either.
     */
    @Test
    void reAddingARemovedCaseToTheSameProgrammeIsACleanInsert() {
        UUID tenant = fixture.createTenant("prog-reAdd-case-" + Uuid7.generate());
        var caseId = new UUID[1];
        var programmeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            programmeId[0] = programmeService.create(new CreateProgrammeRequest(
                    "Programme", customerId, null, null, null, null)).id();
            caseId[0] = journey.newCase(tenant).getId();
            membershipService.addJourney(programmeId[0], new AddJourneyRequest(caseId[0]));
            membershipService.removeJourney(programmeId[0], caseId[0]);
        });

        fixture.runAs(tenant, () -> membershipService.addJourney(programmeId[0], new AddJourneyRequest(caseId[0])));

        fixture.runAs(tenant, () -> {
            ProgrammeDetailView view = programmeService.get(programmeId[0]);
            assertThat(view.journeys()).extracting(ProgrammeJourneyView::caseId).containsExactly(caseId[0]);
        });
    }

    /**
     * programme_case_active_uq is scoped to the case being in ANY currently-active
     * link, tenant-wide -- not to one programme -- so a case still actively linked
     * to a DIFFERENT programme must be refused as a conflict here too, without
     * ever reaching a raw DataIntegrityViolationException. Proves the fix reads
     * the actual partial-index shape rather than a per-programme uniqueness rule.
     */
    @Test
    void addingACaseAlreadyActivelyLinkedToADifferentProgrammeIsAConflictNotA500() {
        UUID tenant = fixture.createTenant("prog-case-linked-elsewhere-" + Uuid7.generate());
        var caseId = new UUID[1];
        var programmeA = new UUID[1];
        var programmeB = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            programmeA[0] = programmeService.create(new CreateProgrammeRequest(
                    "Programme A", customerId, null, null, null, null)).id();
            programmeB[0] = programmeService.create(new CreateProgrammeRequest(
                    "Programme B", customerId, null, null, null, null)).id();
            caseId[0] = journey.newCase(tenant).getId();
            membershipService.addJourney(programmeA[0], new AddJourneyRequest(caseId[0]));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> membershipService.addJourney(
                programmeB[0], new AddJourneyRequest(caseId[0]))))
                .isInstanceOf(ProgrammeJourneyAlreadyLinkedException.class);
    }

    // ---- Finding 3: no write test at a narrower scope than full Administrator ----

    /**
     * Same shape as ProgrammeServiceTest's own narrowest-scope update test: a
     * TEAM-scoped programme.manage holder, over a programme owned by their own
     * team, genuinely succeeds at one of the four membership writes.
     */
    @Test
    void aTeamScopedProgrammeManageHolderCanAddAParticipantWithinTheirOwnScope() {
        UUID tenant = fixture.createTenant("prog-team-scoped-manage-" + Uuid7.generate());
        var teamManager = new UUID[1];
        var targetUser = new UUID[1];
        var programmeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Delivery Team");
            teamManager[0] = fixture.createUser(tenant, "team-manager+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamManager[0], team);
            targetUser[0] = fixture.createUser(tenant, "target+" + Uuid7.generate() + "@example.com");

            // TEAM-scoped programme.manage: narrower than the seeded ALL-only shape
            // today, per CLAUDE.md's "at least one write test at the narrowest
            // catalogued scope" convention. customer.view/user.view granted at ALL
            // alongside it -- unrelated to what this test is actually proving, same
            // as every other test in this file/ProgrammeServiceTest.
            grant(teamManager[0], Map.of(
                    PermissionKeys.PROGRAMME_MANAGE, Scope.TEAM,
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL,
                    PermissionKeys.USER_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            programmeId[0] = programmeService.create(new CreateProgrammeRequest(
                    "Team Programme", customerId, null, null, null, team)).id();
        });

        fixture.runAsUser(tenant, teamManager[0], () -> membershipService.addParticipant(programmeId[0],
                new AddProgrammeParticipantRequest(targetUser[0], RelationshipType.PARTICIPANT, false)));

        fixture.runAs(tenant, () -> {
            List<ProgrammeParticipant> rows = participantRepository.findByProgrammeId(programmeId[0]).stream()
                    .filter(p -> targetUser[0].equals(p.getUserId())).toList();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getStatus()).isEqualTo(ProgrammeParticipantStatus.ACTIVE);
        });
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }
}
