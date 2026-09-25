package co.ara.onboarding.scoping;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.agreement.AgreementRepository;
import co.ara.onboarding.agreement.AgreementStatus;
import co.ara.onboarding.agreement.AgreementTestSupport;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgreementAudienceFilter} (design spec 6.4/6.5, Task 6) -- the portal half
 * mirrors {@code security.PortalVisibilityTest}'s own shape for {@code Document};
 * the internal half mirrors {@code security.DocumentAudienceTest}'s "ALL-scoped
 * reader is unaffected" baseline. Every portal test grants nothing explicitly -- a
 * PORTAL actor resolves agreement.view at ALL automatically
 * ({@code PortalPermissions.forContact()}, Task 6), narrowed entirely by this
 * filter.
 */
class AgreementAudienceFilterTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired AgreementRepository agreements;
    @Autowired AuthorizedQuery authorizedQuery;
    @Autowired AgreementTestSupport agreementFixtures;
    @Autowired AgreementAudienceFilter filter;

    @Test
    void aPortalContactSeesNothingInDraftUnderReviewOrApproved() {
        UUID tenant = fixture.createTenant("agr-aud-pre-send");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerId, "reader@agr-aud-pre-send.example");

        fixture.runAs(tenant, () -> {
            Case c = journey.newCaseForCustomer(tenant, customerId);
            agreementFixtures.agreementRowInStatus(tenant, c.getId(), AgreementStatus.DRAFT);
            agreementFixtures.agreementRowInStatus(tenant, c.getId(), AgreementStatus.UNDER_REVIEW);
            agreementFixtures.agreementRowInStatus(tenant, c.getId(), AgreementStatus.APPROVED);
        });

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(agreements, Agreement.class,
                        PermissionKeys.AGREEMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("DRAFT, UNDER_REVIEW and APPROVED are all internal-drafting states, "
                                + "invisible to the portal")
                        .isEmpty());
    }

    @Test
    void aPortalContactSeesTheirCustomersAgreementFromSentOnward() {
        UUID tenant = fixture.createTenant("agr-aud-sent-onward");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerId, "reader@agr-aud-sent-onward.example");

        var sentRef = new AtomicReference<UUID>();
        var awaitingRef = new AtomicReference<UUID>();
        var signedRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            Case c = journey.newCaseForCustomer(tenant, customerId);
            sentRef.set(agreementFixtures.agreementRowInStatus(tenant, c.getId(), AgreementStatus.SENT).getId());
            awaitingRef.set(agreementFixtures.agreementRowInStatus(tenant, c.getId(), AgreementStatus.AWAITING_SIGNATURE).getId());
            signedRef.set(agreementFixtures.agreementRowInStatus(tenant, c.getId(), AgreementStatus.SIGNED).getId());
        });

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(agreements, Agreement.class,
                        PermissionKeys.AGREEMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("SENT, AWAITING_SIGNATURE and SIGNED are all visible to the portal")
                        .extracting(Agreement::getId)
                        .containsExactlyInAnyOrder(sentRef.get(), awaitingRef.get(), signedRef.get()));
    }

    @Test
    void aPortalContactNeverSeesAnotherCustomersAgreement() {
        UUID tenant = fixture.createTenant("agr-aud-cross-customer");
        UUID customerA = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID customerB = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Globex", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerA, "reader@agr-aud-cross-customer.example");

        fixture.runAs(tenant, () -> {
            Case cB = journey.newCaseForCustomer(tenant, customerB);
            // SENT -- the most permissive status possible -- proving the customer
            // gate refuses it regardless of status.
            agreementFixtures.agreementRowInStatus(tenant, cB.getId(), AgreementStatus.SENT);
        });

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(agreements, Agreement.class,
                        PermissionKeys.AGREEMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("a contact of customer A must not read customer B's SENT agreement")
                        .isEmpty());
    }

    @Test
    void aPortalUserWithNoActiveContactSeesNothing() {
        UUID tenant = fixture.createTenant("agr-aud-retired");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerId, "retired@agr-aud-retired.example");

        var docRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            Case c = journey.newCaseForCustomer(tenant, customerId);
            docRef.set(agreementFixtures.agreementRowInStatus(tenant, c.getId(), AgreementStatus.SENT).getId());
        });

        // Sanity: visible before retirement.
        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(agreements, Agreement.class,
                        PermissionKeys.AGREEMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("sanity check: visible before retirement")
                        .extracting(Agreement::getId).containsExactly(docRef.get()));

        fixture.retireContactFor(tenant, contactUserId);

        // The filter itself must be independently fail-closed, not merely
        // unreachable because permission resolution already refuses upstream --
        // the same double-check PortalVisibilityTest.aRetiredContactReadsNothing
        // performs for DocumentAudienceFilter.
        AuthContext ctx = new AuthContext(tenant, contactUserId, UserType.PORTAL, null, Set.of());
        var spec = filter.audience(ctx, PermissionKeys.AGREEMENT_VIEW);
        fixture.runAs(tenant, () ->
                assertThat(agreements.findAll(spec))
                        .as("the audience filter itself must fail closed for a retired contact")
                        .isEmpty());

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(agreements, Agreement.class,
                        PermissionKeys.AGREEMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("through the full stack, a retired contact resolves no permission at all")
                        .isEmpty());
    }

    @Test
    void aCancelledAgreementIsInvisibleToThePortal() {
        UUID tenant = fixture.createTenant("agr-aud-cancelled");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerId, "reader@agr-aud-cancelled.example");

        fixture.runAs(tenant, () -> {
            Case c = journey.newCaseForCustomer(tenant, customerId);
            agreementFixtures.agreementRowInStatus(tenant, c.getId(), AgreementStatus.CANCELLED);
        });

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(agreements, Agreement.class,
                        PermissionKeys.AGREEMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("a CANCELLED agreement is never visible to the portal, even having once been SENT")
                        .isEmpty());
    }

    /**
     * Internal readers pass through this filter untouched (a cb.conjunction());
     * case scope (AgreementDescriptor) is what governs them. An ALL-scoped
     * internal holder must see even a DRAFT agreement, which the portal branch
     * above proves is invisible to a portal contact -- the sharpest possible
     * contrast between the two branches.
     */
    @Test
    void internalAllScopedReadersAreNotNarrowedByTheFilter() {
        UUID tenant = fixture.createTenant("agr-aud-internal-all");
        var userRef = new AtomicReference<UUID>();
        var docRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            userRef.set(fixture.createUser(tenant, "admin@agr-aud-internal-all.example"));
            Case c = journey.newCase(tenant);
            docRef.set(agreementFixtures.agreementRowInStatus(tenant, c.getId(), AgreementStatus.DRAFT).getId());
        });
        fixture.grantAtAllScope(tenant, userRef.get(), PermissionKeys.AGREEMENT_VIEW);

        fixture.runAsUser(tenant, userRef.get(), () ->
                assertThat(authorizedQuery.findAll(agreements, Agreement.class,
                        PermissionKeys.AGREEMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("an ALL-scoped internal reader sees a DRAFT agreement the portal branch never would")
                        .extracting(Agreement::getId).containsExactly(docRef.get()));
    }
}
