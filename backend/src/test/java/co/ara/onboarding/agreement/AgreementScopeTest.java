package co.ara.onboarding.agreement;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Record-level scope negatives for agreements, resolved through {@code AgreementDescriptor}'s
 * case-relative DEPARTMENT / TEAM / ASSIGNED predicates. Includes the CLAUDE.md "narrowest scope"
 * writes for {@code send} and {@code record}, which the lifecycle tests only exercise at ALL.
 */
class AgreementScopeTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementSignatureService signatureService;
    @Autowired JourneyFixtures journey;
    @Autowired RoleService roles;

    /** A user holding {@code grants} (with workflow.view at ALL, the nested stage lookup every write makes). */
    private UUID userWith(UUID tenant, Map<String, Scope> grants) {
        UUID user = fixture.createUser(tenant, "scoped+" + Uuid7.generate() + "@example.com");
        var all = new java.util.HashMap<>(grants);
        all.put(PermissionKeys.WORKFLOW_VIEW, Scope.ALL);
        roles.assignRole(user, roles.createRole("Fixture Role " + Uuid7.generate(), "", all));
        return user;
    }

    @Test
    void aTeamHolderCannotReadOrWriteAnotherTeamsAgreement() {
        UUID tenant = fixture.createTenant("agr-scope-team");
        var actor = new UUID[1];
        var caseA = new UUID[1];
        var caseB = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID teamA = fixture.createTeam(tenant, "Team A " + Uuid7.generate());
            UUID teamB = fixture.createTeam(tenant, "Team B " + Uuid7.generate());
            actor[0] = userWith(tenant, Map.of(
                    PermissionKeys.AGREEMENT_VIEW, Scope.TEAM,
                    PermissionKeys.AGREEMENT_MANAGE, Scope.TEAM,
                    PermissionKeys.AGREEMENT_SIGN_RECORD, Scope.TEAM,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.TEAM,
                    PermissionKeys.CASE_VIEW, Scope.TEAM));
            fixture.addToTeam(tenant, actor[0], teamA);
            caseA[0] = support.openCase(tenant, WriteScope.ANY, null, null, teamA);
            caseB[0] = support.openCase(tenant, WriteScope.ANY, null, null, teamB);
        });
        var own = support.drive(tenant, caseA[0], AgreementStatus.APPROVED, 1, null, null);
        var other = support.drive(tenant, caseB[0], AgreementStatus.APPROVED, 1, null, null);
        var otherBefore = support.snapshot(tenant, other.agreementId());

        // Narrowest scope: a TEAM holder sends, then records the last signature, on their own team's case.
        var sent = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, actor[0], () -> sent.set(agreementService.send(own.agreementId(), own.lockVersion())));
        assertThat(sent.get().agreement().status()).isEqualTo(AgreementStatus.SENT);
        var signed = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, actor[0], () -> signed.set(signatureService.record(own.agreementId(),
                new RecordSignatureRequest(own.signatoryIds().get(0), LocalDate.now(clock), "Wet ink",
                        sent.get().agreement().lockVersion()), null, 0)));
        assertThat(signed.get().agreement().status()).isEqualTo(AgreementStatus.SIGNED);

        // Another team's agreement: invisible and unwritable, all NoSuchElementException.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> agreementService.get(other.agreementId())))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0],
                () -> agreementService.send(other.agreementId(), other.lockVersion())))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> agreementService.cancel(
                other.agreementId(), new CancelAgreementRequest("Not mine", other.lockVersion()))))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(support.snapshot(tenant, other.agreementId())).isEqualTo(otherBefore);

        // Once the admin sends it, recording is the other half of the same refusal.
        var otherSent = new AtomicReference<AgreementDetailView>();
        fixture.runAs(tenant, () -> otherSent.set(agreementService.send(other.agreementId(), other.lockVersion())));
        var sentBefore = support.snapshot(tenant, other.agreementId());
        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> signatureService.record(
                other.agreementId(), new RecordSignatureRequest(other.signatoryIds().get(0), LocalDate.now(clock),
                        "Wet ink", otherSent.get().agreement().lockVersion()), null, 0)))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(support.snapshot(tenant, other.agreementId())).isEqualTo(sentBefore);
        assertThat(sentBefore.signatures()).isZero();

        // Reads are scoped too: the list holds exactly the one team-A agreement, case B's is empty.
        var listed = new AtomicReference<List<AgreementView>>();
        var forCaseB = new AtomicReference<List<AgreementView>>();
        fixture.runAsUser(tenant, actor[0], () -> {
            listed.set(agreementService.list(null, Pageable.unpaged()).getContent());
            forCaseB.set(agreementService.forCase(caseB[0]));
        });
        assertThat(listed.get()).extracting(AgreementView::id).containsExactly(own.agreementId());
        assertThat(forCaseB.get()).isEmpty();
    }

    @Test
    void anAssignedViewerSeesOnlyAgreementsOnCasesTheyParticipateIn() {
        UUID tenant = fixture.createTenant("agr-scope-assigned");
        var viewer = new UUID[1];
        var caseIn = new UUID[1];
        var caseOut = new UUID[1];
        fixture.runAs(tenant, () -> {
            // agreement.manage is catalogued at ALL/DEPARTMENT/TEAM only, so ASSIGNED is a read-only scope here.
            viewer[0] = userWith(tenant, Map.of(PermissionKeys.AGREEMENT_VIEW, Scope.ASSIGNED));
            caseIn[0] = support.openCase(tenant, WriteScope.ANY, null, null, null);
            caseOut[0] = support.openCase(tenant, WriteScope.ANY, null, null, null);
            journey.addParticipant(tenant, caseIn[0], viewer[0], RelationshipType.PARTICIPANT, ParticipantStatus.ACTIVE);
        });
        var visible = support.drive(tenant, caseIn[0], AgreementStatus.DRAFT, 0, null, null);
        var hidden = support.drive(tenant, caseOut[0], AgreementStatus.DRAFT, 0, null, null);
        var hiddenBefore = support.snapshot(tenant, hidden.agreementId());

        var listed = new AtomicReference<List<AgreementView>>();
        var summary = new AtomicReference<AgreementSummaryView>();
        fixture.runAsUser(tenant, viewer[0], () -> {
            listed.set(agreementService.list(null, Pageable.unpaged()).getContent());
            summary.set(agreementService.summary());
        });
        assertThat(listed.get()).extracting(AgreementView::id).containsExactly(visible.agreementId());
        assertThat(summary.get().draft()).isEqualTo(1L);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, viewer[0], () -> agreementService.get(hidden.agreementId())))
                .isInstanceOf(NoSuchElementException.class);
        var forHiddenCase = new AtomicReference<List<AgreementView>>();
        fixture.runAsUser(tenant, viewer[0], () -> forHiddenCase.set(agreementService.forCase(caseOut[0])));
        assertThat(forHiddenCase.get()).isEmpty();
        assertThat(support.snapshot(tenant, hidden.agreementId())).isEqualTo(hiddenBefore);
    }

    @Test
    void aDepartmentHolderIsBoundToTheirDepartment() {
        UUID tenant = fixture.createTenant("agr-scope-department");
        var actor = new UUID[1];
        var caseOwn = new UUID[1];
        var caseOther = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID mine = fixture.createDepartment(tenant, "Mine " + Uuid7.generate());
            UUID theirs = fixture.createDepartment(tenant, "Theirs " + Uuid7.generate());
            UUID user = fixture.createUserInDepartment(tenant, "dept+" + Uuid7.generate() + "@example.com", mine);
            roles.assignRole(user, roles.createRole("Fixture Role " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.AGREEMENT_VIEW, Scope.DEPARTMENT,
                    PermissionKeys.AGREEMENT_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL)));
            actor[0] = user;
            caseOwn[0] = support.openCase(tenant, WriteScope.ANY, null, mine, null);
            caseOther[0] = support.openCase(tenant, WriteScope.ANY, null, theirs, null);
        });
        var own = support.drive(tenant, caseOwn[0], AgreementStatus.DRAFT, 0, null, null);
        var other = support.drive(tenant, caseOther[0], AgreementStatus.DRAFT, 0, null, null);
        var otherBefore = support.snapshot(tenant, other.agreementId());

        var patched = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, actor[0], () -> patched.set(agreementService.patch(own.agreementId(),
                new PatchAgreementRequest("Renamed", null, null, null, null, null, own.lockVersion()))));
        assertThat(patched.get().agreement().name()).isEqualTo("Renamed");

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> agreementService.get(other.agreementId())))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> agreementService.patch(
                other.agreementId(), new PatchAgreementRequest("Hijacked", null, null, null, null, null,
                        other.lockVersion()))))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(support.snapshot(tenant, other.agreementId())).isEqualTo(otherBefore);

        var listed = new AtomicReference<List<AgreementView>>();
        fixture.runAsUser(tenant, actor[0], () -> listed.set(agreementService.list(null, Pageable.unpaged()).getContent()));
        assertThat(listed.get()).extracting(AgreementView::id).containsExactly(own.agreementId());
    }
}
