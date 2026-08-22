package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.DescriptorRegistry;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Approval;
import co.ara.onboarding.journey.ApprovalRepository;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scope resolution for the journey module's four descriptors (Task 11). Every
 * descriptor test builds an AuthContext directly and asserts against the
 * Specification it returns, the same shape DescriptorRegistryTest already
 * established for Customer -- setup and assertion run inside a single
 * {@link TenantFixture#runAs} block so the repositories are RLS-bound throughout.
 * Only the narrow-scope write test goes through a real role and AuthorizedQuery,
 * because that is the one claim ("a write is refused, not just a read") a
 * hand-built AuthContext cannot prove.
 */
class JourneyScopingTest extends PostgresTestBase {

    @Autowired DescriptorRegistry registry;
    @Autowired CaseRepository cases;
    @Autowired MilestoneRepository milestones;
    @Autowired RequirementRepository requirements;
    @Autowired ApprovalRepository approvals;
    @Autowired AuthorizedQuery authorizedQuery;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;

    /** DEPARTMENT and TEAM resolve against the case's own copied columns. */
    @Test
    void departmentScopeSeesOnlyItsDepartmentsCases() {
        UUID tenant = fixture.createTenant("scope-case-dept");
        fixture.runAs(tenant, () -> {
            UUID actor = fixture.createUser(tenant, "dept@scope-case-dept.example");
            UUID department = fixture.createDepartment(tenant, "Onboarding");
            UUID otherDepartment = fixture.createDepartment(tenant, "Other");

            Case inDepartment = journey.newCase(tenant, null, department, null);
            journey.newCase(tenant, null, otherDepartment, null);
            journey.newCase(tenant, null, null, null);

            var descriptor = registry.forEntity(Case.class);
            var spec = descriptor.departmentScope(
                    new AuthContext(tenant, actor, UserType.INTERNAL, department, Set.of()));

            assertThat(cases.findAll(spec))
                    .extracting(Case::getId)
                    .containsExactly(inDepartment.getId());
        });
    }

    @Test
    void teamScopeSeesOnlyItsTeamsCases() {
        UUID tenant = fixture.createTenant("scope-case-team");
        fixture.runAs(tenant, () -> {
            UUID actor = fixture.createUser(tenant, "team@scope-case-team.example");
            UUID team = fixture.createTeam(tenant, "Onboarding Team");
            UUID otherTeam = fixture.createTeam(tenant, "Other Team");

            Case inTeam = journey.newCase(tenant, null, null, team);
            journey.newCase(tenant, null, null, otherTeam);
            journey.newCase(tenant, null, null, null);

            var descriptor = registry.forEntity(Case.class);
            var spec = descriptor.teamScope(
                    new AuthContext(tenant, actor, UserType.INTERNAL, null, Set.of(team)));

            assertThat(cases.findAll(spec))
                    .extracting(Case::getId)
                    .containsExactly(inTeam.getId());
        });
    }

    /**
     * ASSIGNED resolves through case_participant, which is the first real use of
     * assignedRelationships(): sub-project 1 declared the set on every descriptor and
     * then resolved ASSIGNED from a single column each time, so the set was decorative.
     */
    @Test
    void assignedScopeResolvesThroughParticipantRows() {
        UUID tenant = fixture.createTenant("scope-assigned");
        fixture.runAs(tenant, () -> {
            UUID actor = fixture.createUser(tenant, "assigned@scope-assigned.example");

            Case participantCase = journey.newCase(tenant);
            journey.addParticipant(tenant, participantCase.getId(), actor,
                    RelationshipType.PARTICIPANT, ParticipantStatus.ACTIVE);

            Case creatorOnlyCase = journey.newCase(tenant);
            journey.addParticipant(tenant, creatorOnlyCase.getId(), actor,
                    RelationshipType.CREATOR, ParticipantStatus.ACTIVE);

            Case removedCase = journey.newCase(tenant);
            journey.addParticipant(tenant, removedCase.getId(), actor,
                    RelationshipType.PARTICIPANT, ParticipantStatus.REMOVED);

            var descriptor = registry.forEntity(Case.class);
            var spec = descriptor.assignedScope(
                    new AuthContext(tenant, actor, UserType.INTERNAL, null, Set.of()));

            assertThat(cases.findAll(spec))
                    .as("PARTICIPANT/ACTIVE is visible; CREATOR and REMOVED are not")
                    .extracting(Case::getId)
                    .containsExactly(participantCase.getId());
        });
    }

    /**
     * CREATOR is excluded on CustomerDescriptor's reasoning: having once created a case
     * is not an ongoing relationship to it, so a salesperson who opens a case and hands
     * it over loses ASSIGNED access rather than keeping it forever.
     */
    @Test
    void creatorAloneDoesNotConferAssignedAccess() {
        UUID tenant = fixture.createTenant("scope-creator-only");
        fixture.runAs(tenant, () -> {
            UUID actor = fixture.createUser(tenant, "creator@scope-creator-only.example");

            Case c = journey.newCase(tenant);
            journey.addParticipant(tenant, c.getId(), actor,
                    RelationshipType.CREATOR, ParticipantStatus.ACTIVE);

            var descriptor = registry.forEntity(Case.class);
            var spec = descriptor.assignedScope(
                    new AuthContext(tenant, actor, UserType.INTERNAL, null, Set.of()));

            assertThat(cases.findAll(spec))
                    .as("CREATOR alone must not confer ASSIGNED access")
                    .isEmpty();
        });
    }

    /** Milestones, requirements and approvals inherit the case's scope. */
    @Test
    void childrenResolveThroughTheirCase() {
        UUID tenant = fixture.createTenant("scope-children");
        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Children Department");
            UUID otherDepartment = fixture.createDepartment(tenant, "Other Department");
            UUID requester = fixture.createUser(tenant, "requester@scope-children.example");

            Case inDepartment = journey.newCase(tenant, null, department, null);
            Milestone milestone = journey.newMilestone(tenant, inDepartment);
            Requirement requirement = journey.newRequirement(tenant, inDepartment, milestone);
            UUID stageId = journey.newStage(tenant, inDepartment.getVersionId());
            Approval approval = journey.newApproval(tenant, inDepartment.getId(), stageId, requester);

            // A case in a different department, with children of its own, so the
            // assertions below prove filtering rather than merely "finds something".
            Case elsewhere = journey.newCase(tenant, null, otherDepartment, null);
            journey.newRequirement(tenant, elsewhere, journey.newMilestone(tenant, elsewhere));

            AuthContext ctx = new AuthContext(tenant, requester, UserType.INTERNAL, department, Set.of());

            assertThat(milestones.findAll(registry.forEntity(Milestone.class).departmentScope(ctx)))
                    .extracting(Milestone::getId)
                    .containsExactly(milestone.getId());
            assertThat(requirements.findAll(registry.forEntity(Requirement.class).departmentScope(ctx)))
                    .extracting(Requirement::getId)
                    .containsExactly(requirement.getId());
            assertThat(approvals.findAll(registry.forEntity(Approval.class).departmentScope(ctx)))
                    .extracting(Approval::getId)
                    .containsExactly(approval.getId());
        });
    }

    /** Fail closed: no department, no teams, no participation means no rows. */
    @Test
    void anActorWithNothingSeesNothing() {
        UUID tenant = fixture.createTenant("scope-nothing");
        fixture.runAs(tenant, () -> {
            UUID actor = fixture.createUser(tenant, "nobody@scope-nothing.example");
            UUID department = fixture.createDepartment(tenant, "Somebody Else's Department");
            UUID team = fixture.createTeam(tenant, "Somebody Else's Team");
            journey.newCase(tenant, null, department, team);

            AuthContext ctx = new AuthContext(tenant, actor, UserType.INTERNAL, null, Set.of());
            var descriptor = registry.forEntity(Case.class);

            assertThat(cases.findAll(descriptor.departmentScope(ctx))).isEmpty();
            assertThat(cases.findAll(descriptor.teamScope(ctx))).isEmpty();
            assertThat(cases.findAll(descriptor.assignedScope(ctx))).isEmpty();
        });
    }

    /**
     * The narrow-scope WRITE test. Every write case in sub-project 1's UserAdminTest
     * granted USER_MANAGE at ALL, which is exactly why the escalation survived: not one
     * test asked what a narrow write scope does.
     *
     * MilestoneService.complete does not exist yet -- it is Task 16's deliverable, four
     * tasks away -- so this asserts one layer below where a future service-level test
     * will sit: AuthorizedQuery.getById is exactly what complete() must call before
     * mutating anything, and it is what MilestoneDescriptor's ASSIGNED scope actually
     * gates. Task 16 will add its own test once complete() exists; this does not
     * preclude that.
     */
    @Test
    void completingAMilestoneAtAssignedScopeIsRefusedForSomeoneElsesCase() {
        UUID tenant = fixture.createTenant("scope-narrow-write");
        var actor = new AtomicReference<UUID>();
        var otherPeoplesMilestoneId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID actorId = fixture.createUser(tenant, "narrow@scope-narrow-write.example");
            actor.set(actorId);

            Case theirCase = journey.newCase(tenant);
            Milestone m = journey.newMilestone(tenant, theirCase);
            otherPeoplesMilestoneId.set(m.getId());
            // actorId is deliberately never added as a participant on theirCase.

            UUID role = roles.createRole("Narrow Milestone Completer", "",
                    Map.of(PermissionKeys.MILESTONE_COMPLETE, Scope.ASSIGNED));
            roles.assignRole(actorId, role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor.get(), () ->
                authorizedQuery.getById(milestones, Milestone.class,
                        PermissionKeys.MILESTONE_COMPLETE, otherPeoplesMilestoneId.get())))
                .isInstanceOf(NoSuchElementException.class);
    }
}
