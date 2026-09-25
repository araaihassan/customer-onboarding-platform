package co.ara.onboarding.scoping;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.agreement.AgreementRepository;
import co.ara.onboarding.agreement.AgreementSignatory;
import co.ara.onboarding.agreement.AgreementSignatoryRepository;
import co.ara.onboarding.agreement.AgreementStatus;
import co.ara.onboarding.agreement.AgreementTestSupport;
import co.ara.onboarding.agreement.SignatoryKind;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.DescriptorRegistry;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scope resolution for {@link AgreementDescriptor} and its four child descriptors
 * (Task 6) -- the {@code scoping.JourneyScopingTest} shape: setup and assertion
 * run inside a single {@link TenantFixture#runAs} block, reading the descriptor's
 * own Specification directly via {@link DescriptorRegistry}, except for the
 * narrow-scope child-row test, which goes through a real role and
 * {@link AuthorizedQuery} because "an out-of-scope child row is refused" is not a
 * claim a hand-built AuthContext against a bare Specification can prove on its
 * own -- {@code AuthorizedQuery.getById} is what actually 404s.
 */
class AgreementScopingTest extends PostgresTestBase {

    @Autowired DescriptorRegistry registry;
    @Autowired AgreementRepository agreements;
    @Autowired AgreementSignatoryRepository signatories;
    @Autowired AuthorizedQuery authorizedQuery;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired AgreementTestSupport agreementFixtures;

    @Test
    void aTeamScopedViewerSeesAgreementsOnTheirTeamsCasesOnly() {
        UUID tenant = fixture.createTenant("agr-scope-team");
        fixture.runAs(tenant, () -> {
            UUID actor = fixture.createUser(tenant, "team@agr-scope-team.example");
            UUID team = fixture.createTeam(tenant, "Onboarding Team");
            UUID otherTeam = fixture.createTeam(tenant, "Other Team");

            Case inTeam = journey.newCase(tenant, null, null, team);
            Agreement inTeamAgreement = agreementFixtures.agreementRowInStatus(tenant, inTeam.getId(), AgreementStatus.DRAFT);

            Case inOtherTeam = journey.newCase(tenant, null, null, otherTeam);
            agreementFixtures.agreementRowInStatus(tenant, inOtherTeam.getId(), AgreementStatus.DRAFT);

            Case noTeam = journey.newCase(tenant);
            agreementFixtures.agreementRowInStatus(tenant, noTeam.getId(), AgreementStatus.DRAFT);

            var descriptor = registry.forEntity(Agreement.class);
            var spec = descriptor.teamScope(
                    new AuthContext(tenant, actor, UserType.INTERNAL, null, Set.of(team)));

            assertThat(agreements.findAll(spec))
                    .extracting(Agreement::getId)
                    .containsExactly(inTeamAgreement.getId());
        });
    }

    @Test
    void aDepartmentScopedViewerSeesAgreementsOnTheirDepartmentsCasesOnly() {
        UUID tenant = fixture.createTenant("agr-scope-dept");
        fixture.runAs(tenant, () -> {
            UUID actor = fixture.createUser(tenant, "dept@agr-scope-dept.example");
            UUID department = fixture.createDepartment(tenant, "Onboarding");
            UUID otherDepartment = fixture.createDepartment(tenant, "Other");

            Case inDepartment = journey.newCase(tenant, null, department, null);
            Agreement inDepartmentAgreement =
                    agreementFixtures.agreementRowInStatus(tenant, inDepartment.getId(), AgreementStatus.DRAFT);

            Case inOtherDepartment = journey.newCase(tenant, null, otherDepartment, null);
            agreementFixtures.agreementRowInStatus(tenant, inOtherDepartment.getId(), AgreementStatus.DRAFT);

            Case noDepartment = journey.newCase(tenant);
            agreementFixtures.agreementRowInStatus(tenant, noDepartment.getId(), AgreementStatus.DRAFT);

            var descriptor = registry.forEntity(Agreement.class);
            var spec = descriptor.departmentScope(
                    new AuthContext(tenant, actor, UserType.INTERNAL, department, Set.of()));

            assertThat(agreements.findAll(spec))
                    .extracting(Agreement::getId)
                    .containsExactly(inDepartmentAgreement.getId());
        });
    }

    @Test
    void anAssignedViewerSeesOnlyCasesTheyPersonallyParticipateIn() {
        UUID tenant = fixture.createTenant("agr-scope-assigned");
        fixture.runAs(tenant, () -> {
            UUID actor = fixture.createUser(tenant, "assigned@agr-scope-assigned.example");

            Case participantCase = journey.newCase(tenant);
            journey.addParticipant(tenant, participantCase.getId(), actor,
                    RelationshipType.PARTICIPANT, ParticipantStatus.ACTIVE);
            Agreement participantAgreement =
                    agreementFixtures.agreementRowInStatus(tenant, participantCase.getId(), AgreementStatus.DRAFT);

            Case unrelatedCase = journey.newCase(tenant);
            agreementFixtures.agreementRowInStatus(tenant, unrelatedCase.getId(), AgreementStatus.DRAFT);

            var descriptor = registry.forEntity(Agreement.class);
            var spec = descriptor.assignedScope(
                    new AuthContext(tenant, actor, UserType.INTERNAL, null, Set.of()));

            assertThat(agreements.findAll(spec))
                    .as("only the case the actor actually participates in is visible")
                    .extracting(Agreement::getId)
                    .containsExactly(participantAgreement.getId());
        });
    }

    /** Fail closed: no department, no teams, no participation means no rows at any of the three scopes. */
    @Test
    void aViewerWithNoDepartmentAndNoTeamSeesNothingAtThoseScopes() {
        UUID tenant = fixture.createTenant("agr-scope-nothing");
        fixture.runAs(tenant, () -> {
            UUID actor = fixture.createUser(tenant, "nobody@agr-scope-nothing.example");
            UUID department = fixture.createDepartment(tenant, "Somebody Else's Department");
            UUID team = fixture.createTeam(tenant, "Somebody Else's Team");
            Case c = journey.newCase(tenant, null, department, team);
            agreementFixtures.agreementRowInStatus(tenant, c.getId(), AgreementStatus.DRAFT);

            AuthContext ctx = new AuthContext(tenant, actor, UserType.INTERNAL, null, Set.of());
            var descriptor = registry.forEntity(Agreement.class);

            assertThat(agreements.findAll(descriptor.departmentScope(ctx))).isEmpty();
            assertThat(agreements.findAll(descriptor.teamScope(ctx))).isEmpty();
            assertThat(agreements.findAll(descriptor.assignedScope(ctx))).isEmpty();
        });
    }

    /**
     * A signatory on an in-department agreement's case is reachable under
     * agreement.manage at DEPARTMENT scope; the identical shape on an
     * out-of-department case is a 404-shaped NoSuchElementException, never a
     * silent read -- proving the two-hop signatory -> agreement -> case
     * resolution actually narrows, not just that it compiles.
     */
    @Test
    void childRowsFollowTheirAgreementsCase() {
        UUID tenant = fixture.createTenant("agr-scope-children");
        var actor = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var inScopeSignatoryId = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var outOfScopeSignatoryId = new java.util.concurrent.atomic.AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Onboarding");
            UUID otherDepartment = fixture.createDepartment(tenant, "Other");
            UUID actorId = fixture.createUserInDepartment(tenant, "children@agr-scope-children.example", department);
            actor.set(actorId);

            Case inDepartment = journey.newCase(tenant, null, department, null);
            Agreement inDepartmentAgreement =
                    agreementFixtures.agreementRowInStatus(tenant, inDepartment.getId(), AgreementStatus.DRAFT);
            inScopeSignatoryId.set(newSignatory(tenant, inDepartmentAgreement.getId(), actorId));

            Case inOtherDepartment = journey.newCase(tenant, null, otherDepartment, null);
            Agreement otherDepartmentAgreement =
                    agreementFixtures.agreementRowInStatus(tenant, inOtherDepartment.getId(), AgreementStatus.DRAFT);
            outOfScopeSignatoryId.set(newSignatory(tenant, otherDepartmentAgreement.getId(), actorId));

            UUID role = roles.createRole("Narrow Agreement Manager", "",
                    Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.DEPARTMENT));
            roles.assignRole(actorId, role);
        });

        fixture.runAsUser(tenant, actor.get(), () -> {
            AgreementSignatory loaded = authorizedQuery.getById(signatories, AgreementSignatory.class,
                    PermissionKeys.AGREEMENT_MANAGE, inScopeSignatoryId.get());
            assertThat(loaded.getId()).isEqualTo(inScopeSignatoryId.get());
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor.get(), () ->
                authorizedQuery.getById(signatories, AgreementSignatory.class,
                        PermissionKeys.AGREEMENT_MANAGE, outOfScopeSignatoryId.get())))
                .as("a signatory on an out-of-department case is 404, never a silent read")
                .isInstanceOf(NoSuchElementException.class);
    }

    private UUID newSignatory(UUID tenant, UUID agreementId, UUID userId) {
        AgreementSignatory s = new AgreementSignatory();
        s.setId(Uuid7.generate());
        s.setTenantId(tenant);
        s.setAgreementId(agreementId);
        s.setKind(SignatoryKind.INTERNAL);
        s.setUserId(userId);
        s.setDisplayRole("Fixture Signatory");
        s.setSortOrder(0);
        return signatories.saveAndFlush(s).getId();
    }
}
