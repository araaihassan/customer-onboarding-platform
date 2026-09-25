package co.ara.onboarding.agreement;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AgreementRecordMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 11: read paths, {@link AgreementDisplayStatus}'s derived EXPIRED and
 * the lifecycle summary -- the first tests to exercise {@link
 * AgreementService} at all. {@code AgreementTestSupport} (Task 3/4) supplies
 * every fixture row; this class only ever builds ON TOP of it, never
 * duplicates its shape.
 */
class AgreementReadTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementRepository agreements;
    @Autowired AgreementSignatoryRepository signatories;
    @Autowired AgreementVersionRepository versions;
    @Autowired AgreementVersionReviewRepository versionReviews;
    @Autowired AgreementSignatureRepository signatures;
    @Autowired RoleService roles;

    @Test
    void getReturnsTheAgreementWithSignatoriesVersionsAndSignatures() {
        UUID tenant = fixture.createTenant("agr-read-detail");
        var agreementId = new AtomicReference<UUID>();
        var signatoryId = new AtomicReference<UUID>();
        var versionId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            // STRUCTURED_ONLY, not draftAgreementRow's own FILE_BACKED default:
            // agreement_version_file_ck requires a real document_version row for
            // any other record mode, which this test has no reason to build.
            UUID caseId = support.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.STRUCTURED_ONLY);
            Agreement a = agreements.findByCaseId(caseId).get(0);
            agreementId.set(a.getId());

            AgreementSignatory s = new AgreementSignatory();
            s.setId(Uuid7.generate());
            s.setTenantId(tenant);
            s.setAgreementId(a.getId());
            s.setKind(SignatoryKind.INTERNAL);
            s.setUserId(a.getOwnerUserId());
            s.setDisplayRole("Approver");
            s.setSortOrder(0);
            signatories.saveAndFlush(s);
            signatoryId.set(s.getId());

            AgreementVersion v = new AgreementVersion(Uuid7.generate(), tenant, a.getId(), 1, a.getRecordMode(),
                    a.getOwnerUserId(), Instant.now(), a.getOwnerUserId(), "{}", null, null, "a".repeat(64));
            versions.saveAndFlush(v);
            versionId.set(v.getId());

            AgreementVersionReview review = new AgreementVersionReview(Uuid7.generate(), tenant, v.getId(),
                    ReviewDecision.APPROVE, a.getOwnerUserId(), Instant.now(), "Looks fine");
            versionReviews.saveAndFlush(review);

            AgreementSignature sig = new AgreementSignature(Uuid7.generate(), tenant, a.getId(), s.getId(),
                    v.getId(), "b".repeat(64), LocalDate.now(clock), "MANUAL", a.getOwnerUserId(), Instant.now(),
                    null);
            signatures.saveAndFlush(sig);
        });

        fixture.runAs(tenant, () -> {
            AgreementDetailView detail = agreementService.get(agreementId.get());

            assertThat(detail.agreement().id()).isEqualTo(agreementId.get());
            assertThat(detail.signatories()).singleElement().satisfies(sv -> {
                assertThat(sv.id()).isEqualTo(signatoryId.get());
                assertThat(sv.signed()).isTrue();
            });
            assertThat(detail.versions()).singleElement().satisfies(vv -> {
                assertThat(vv.id()).isEqualTo(versionId.get());
                assertThat(vv.reviewDecision()).isEqualTo(ReviewDecision.APPROVE);
            });
            assertThat(detail.signatures()).singleElement()
                    .satisfies(sigv -> assertThat(sigv.signatoryId()).isEqualTo(signatoryId.get()));
        });
    }

    @Test
    void aSignedAgreementWhoseExpiryIsBeforeTodayReadsAsExpired() {
        UUID tenant = fixture.createTenant("agr-read-expired");
        var agreementId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            Agreement a = support.agreementRowInStatus(tenant, c.getId(), AgreementStatus.SIGNED);
            a.setExpiresAt(LocalDate.now(clock));
            a.setUpdatedAt(Instant.now());
            agreements.saveAndFlush(a);
            agreementId.set(a.getId());
        });

        clock.advance(Duration.ofDays(1));

        fixture.runAs(tenant, () -> {
            AgreementDetailView detail = agreementService.get(agreementId.get());
            assertThat(detail.agreement().displayStatus()).isEqualTo(AgreementDisplayStatus.EXPIRED);
        });
    }

    @Test
    void aSignedAgreementExpiringTodayIsStillSigned() {
        UUID tenant = fixture.createTenant("agr-read-not-yet-expired");

        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            Agreement a = support.agreementRowInStatus(tenant, c.getId(), AgreementStatus.SIGNED);
            a.setExpiresAt(LocalDate.now(clock));
            a.setUpdatedAt(Instant.now());
            agreements.saveAndFlush(a);

            AgreementDetailView detail = agreementService.get(a.getId());
            assertThat(detail.agreement().displayStatus()).isEqualTo(AgreementDisplayStatus.SIGNED);
        });
    }

    @Test
    void theStoredStatusStaysSignedWhenItReadsExpired() {
        UUID tenant = fixture.createTenant("agr-read-stored-signed");
        var agreementId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            Agreement a = support.agreementRowInStatus(tenant, c.getId(), AgreementStatus.SIGNED);
            a.setExpiresAt(LocalDate.now(clock));
            a.setUpdatedAt(Instant.now());
            agreements.saveAndFlush(a);
            agreementId.set(a.getId());
        });

        clock.advance(Duration.ofDays(1));

        fixture.runAs(tenant, () -> {
            AgreementDetailView detail = agreementService.get(agreementId.get());
            assertThat(detail.agreement().status()).isEqualTo(AgreementStatus.SIGNED);
            assertThat(detail.agreement().displayStatus()).isEqualTo(AgreementDisplayStatus.EXPIRED);
        });
    }

    private record ExpiryFixture(UUID expiredId, UUID notExpiredId) {}

    /** One SIGNED agreement expiring yesterday (relative to the clock after advancing), one expiring in 60 days. */
    private ExpiryFixture buildExpiringAndNotExpiringSignedAgreements(UUID tenant) {
        var expiredId = new AtomicReference<UUID>();
        var notExpiredId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            Case c1 = journey.newCase(tenant);
            Agreement expired = support.agreementRowInStatus(tenant, c1.getId(), AgreementStatus.SIGNED);
            expired.setExpiresAt(LocalDate.now(clock));
            expired.setUpdatedAt(Instant.now());
            agreements.saveAndFlush(expired);
            expiredId.set(expired.getId());

            Case c2 = journey.newCase(tenant);
            Agreement notExpired = support.agreementRowInStatus(tenant, c2.getId(), AgreementStatus.SIGNED);
            notExpired.setExpiresAt(LocalDate.now(clock).plusDays(60));
            notExpired.setUpdatedAt(Instant.now());
            agreements.saveAndFlush(notExpired);
            notExpiredId.set(notExpired.getId());
        });

        clock.advance(Duration.ofDays(1));
        return new ExpiryFixture(expiredId.get(), notExpiredId.get());
    }

    @Test
    void listFilteredByExpiredReturnsOnlyDerivedExpiredRows() {
        UUID tenant = fixture.createTenant("agr-list-expired");
        ExpiryFixture ids = buildExpiringAndNotExpiringSignedAgreements(tenant);

        fixture.runAs(tenant, () -> {
            List<AgreementView> result =
                    agreementService.list(AgreementDisplayStatus.EXPIRED, Pageable.unpaged()).getContent();
            assertThat(result).extracting(AgreementView::id).containsExactly(ids.expiredId());
        });
    }

    @Test
    void listFilteredBySignedExcludesExpiredRows() {
        UUID tenant = fixture.createTenant("agr-list-signed");
        ExpiryFixture ids = buildExpiringAndNotExpiringSignedAgreements(tenant);

        fixture.runAs(tenant, () -> {
            List<AgreementView> result =
                    agreementService.list(AgreementDisplayStatus.SIGNED, Pageable.unpaged()).getContent();
            assertThat(result).extracting(AgreementView::id).containsExactly(ids.notExpiredId());
        });
    }

    @Test
    void forCaseListsLiveAgreementsBeforeCancelledOnes() {
        UUID tenant = fixture.createTenant("agr-forcase-order");
        var caseId = new AtomicReference<UUID>();
        var liveId = new AtomicReference<UUID>();
        var cancelledOldId = new AtomicReference<UUID>();
        var cancelledNewId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            caseId.set(c.getId());

            cancelledOldId.set(support.agreementRowInStatus(tenant, c.getId(), AgreementStatus.CANCELLED).getId());
            cancelledNewId.set(support.agreementRowInStatus(tenant, c.getId(), AgreementStatus.CANCELLED).getId());
            liveId.set(support.agreementRowInStatus(tenant, c.getId(), AgreementStatus.DRAFT).getId());
        });

        fixture.runAs(tenant, () -> {
            List<AgreementView> result = agreementService.forCase(caseId.get());
            assertThat(result).extracting(AgreementView::id)
                    .containsExactly(liveId.get(), cancelledNewId.get(), cancelledOldId.get());
        });
    }

    /**
     * A TEAM-scoped agreement.view holder's counts must exclude another team's
     * agreements -- the same "scope is a set, applied in the query" property
     * {@code PredicateBuilderTest} proves for {@code customer.view}, exercised
     * here through {@code AgreementService.summary} instead. APPROVED is
     * deliberately the status under test: spec 8's own "under review" bucket
     * unions UNDER_REVIEW and APPROVED, so this doubles as proof of that union.
     */
    @Test
    void summaryCountsApprovedUnderUnderReviewAndIsScopeFiltered() {
        UUID tenant = fixture.createTenant("agr-summary-scope");
        var viewer = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID teamA = fixture.createTeam(tenant, "Team A");
            UUID teamB = fixture.createTeam(tenant, "Team B");

            viewer.set(fixture.createUser(tenant, "team-a-viewer@example.com"));
            fixture.addToTeam(tenant, viewer.get(), teamA);
            UUID role = roles.createRole("Agreement Viewer", "",
                    Map.of(PermissionKeys.AGREEMENT_VIEW, Scope.TEAM));
            roles.assignRole(viewer.get(), role);

            Case caseA = journey.newCase(tenant, null, null, teamA);
            Agreement agreementA = support.agreementRowInStatus(tenant, caseA.getId(), AgreementStatus.APPROVED);
            assertThat(agreementA.getStatus()).isEqualTo(AgreementStatus.APPROVED);

            Case caseB = journey.newCase(tenant, null, null, teamB);
            support.agreementRowInStatus(tenant, caseB.getId(), AgreementStatus.APPROVED);
        });

        fixture.runAsUser(tenant, viewer.get(), () -> {
            AgreementSummaryView summary = agreementService.summary();
            assertThat(summary.underReview()).isEqualTo(1L);
        });
    }

    @Test
    void summaryExpiringWindowIsTodayThroughTodayPlus30Inclusive() {
        UUID tenant = fixture.createTenant("agr-summary-window");

        fixture.runAs(tenant, () -> {
            LocalDate today = LocalDate.now(clock);
            Case c = journey.newCase(tenant);

            Agreement startOfWindow = support.agreementRowInStatus(tenant, c.getId(), AgreementStatus.SIGNED);
            startOfWindow.setExpiresAt(today);
            startOfWindow.setUpdatedAt(Instant.now());
            agreements.saveAndFlush(startOfWindow);

            Agreement endOfWindow = support.agreementRowInStatus(tenant, c.getId(), AgreementStatus.SIGNED);
            endOfWindow.setExpiresAt(today.plusDays(30));
            endOfWindow.setUpdatedAt(Instant.now());
            agreements.saveAndFlush(endOfWindow);

            Agreement justOutsideWindow = support.agreementRowInStatus(tenant, c.getId(), AgreementStatus.SIGNED);
            justOutsideWindow.setExpiresAt(today.plusDays(31));
            justOutsideWindow.setUpdatedAt(Instant.now());
            agreements.saveAndFlush(justOutsideWindow);

            AgreementSummaryView summary = agreementService.summary();
            assertThat(summary.expiringWithin30Days()).isEqualTo(2L);
        });
    }

    @Test
    void aViewerWithoutContactViewStillGetsTheDetailWithNullSignatoryNames() {
        UUID tenant = fixture.createTenant("agr-read-no-contact-view");
        var agreementId = new AtomicReference<UUID>();
        var viewer = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId.set(a.getId());

            UUID contactId = fixture.createContact(tenant, a.getCustomerId(), "signer@example.com");
            AgreementSignatory s = new AgreementSignatory();
            s.setId(Uuid7.generate());
            s.setTenantId(tenant);
            s.setAgreementId(a.getId());
            s.setKind(SignatoryKind.CONTACT);
            s.setContactId(contactId);
            s.setDisplayRole("Signer");
            s.setSortOrder(0);
            signatories.saveAndFlush(s);

            viewer.set(fixture.createUser(tenant, "no-contact-view@example.com"));
            UUID role = roles.createRole("Agreement Only", "",
                    Map.of(PermissionKeys.AGREEMENT_VIEW, Scope.ALL));
            roles.assignRole(viewer.get(), role);
        });

        fixture.runAsUser(tenant, viewer.get(), () -> {
            AgreementDetailView detail = agreementService.get(agreementId.get());
            assertThat(detail.signatories()).singleElement()
                    .satisfies(sv -> assertThat(sv.displayName()).isNull());
        });
    }

    @Test
    void anOutOfScopeAgreementIsNotFound() {
        UUID tenant = fixture.createTenant("agr-read-out-of-scope");
        var agreementId = new AtomicReference<UUID>();
        var viewer = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            // The viewer DOES hold agreement.view (at TEAM), so the gate itself
            // passes and AuthorizedQuery's own scope predicate is what turns this
            // into a 404 -- the whole reason PermissionGateAspect + AuthorizedQuery
            // are two separate checks (a bare zero-grant actor 403s at the gate
            // instead, a different failure mode this test is not about).
            UUID myTeam = fixture.createTeam(tenant, "My Team");
            viewer.set(fixture.createUser(tenant, "narrow-viewer@example.com"));
            fixture.addToTeam(tenant, viewer.get(), myTeam);
            UUID role = roles.createRole("Agreement Viewer", "",
                    Map.of(PermissionKeys.AGREEMENT_VIEW, Scope.TEAM));
            roles.assignRole(viewer.get(), role);

            // A case with no owningTeamId at all -- out of scope for ANY TEAM grant.
            Agreement a = support.draftAgreementRow(tenant);
            agreementId.set(a.getId());
        });

        // Never assert inside the runAs lambda -- catching there leaves the
        // transaction rollback-only and surfaces UnexpectedRollbackException
        // instead of the exception under test.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, viewer.get(), () -> agreementService.get(agreementId.get())))
                .isInstanceOf(NoSuchElementException.class);
    }
}
