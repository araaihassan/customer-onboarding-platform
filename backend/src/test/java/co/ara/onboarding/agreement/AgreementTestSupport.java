package co.ara.onboarding.agreement;

import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AgreementRecordMode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Agreement-row boilerplate for {@code AgreementSchemaTest} and every later task
 * (4 through 21) that needs a live agreement to build on -- the
 * {@code journey.JourneyFixtures} shape. Every method must be called inside
 * {@link TenantFixture#runAs} -- every table here is RLS-protected.
 */
@Component
public class AgreementTestSupport {

    private final AgreementRepository agreements;
    private final CaseRepository cases;
    private final JourneyFixtures journey;
    private final TenantFixture tenantFixture;

    public AgreementTestSupport(AgreementRepository agreements, CaseRepository cases,
                                 JourneyFixtures journey, TenantFixture tenantFixture) {
        this.agreements = agreements;
        this.cases = cases;
        this.journey = journey;
        this.tenantFixture = tenantFixture;
    }

    /**
     * A DRAFT FILE_BACKED agreement on a fresh case/milestone/requirement, owned
     * by a freshly created tenant administrator.
     */
    public Agreement draftAgreementRow(UUID tenant) {
        Case c = journey.newCase(tenant);
        Milestone m = journey.newMilestone(tenant, c);
        Requirement r = journey.newRequirement(tenant, c, m);
        return draftAgreementRowFor(tenant, c.getId(), r.getId());
    }

    /**
     * A DRAFT FILE_BACKED agreement for an already-instantiated case/requirement
     * pair -- what {@code aCancelledAgreementDoesNotBlockItsReplacement} needs to
     * build a second, replacing agreement against the same requirement as an
     * existing one. {@code customerId} is read back off the case row (a case
     * never changes customer, the same denormalisation reasoning the migration's
     * own comment gives for the column).
     */
    public Agreement draftAgreementRowFor(UUID tenant, UUID caseId, UUID requirementId) {
        Case c = cases.findById(caseId).orElseThrow();
        UUID ownerUserId = tenantFixture.createAdministrator(
                tenant, "agreement-fixture+" + Uuid7.generate() + "@fixture.test");

        Agreement a = new Agreement();
        a.setId(Uuid7.generate());
        a.setTenantId(tenant);
        a.setCaseId(caseId);
        a.setRequirementId(requirementId);
        a.setCustomerId(c.getCustomerId());
        a.setName("Fixture Agreement " + Uuid7.generate());
        a.setRecordMode(AgreementRecordMode.FILE_BACKED);
        a.setStatus(AgreementStatus.DRAFT);
        a.setOwnerUserId(ownerUserId);
        a.setLastEditedBy(ownerUserId);
        a.setSignatureProvider(SignatureProviderKind.MANUAL);
        Instant now = Instant.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return agreements.saveAndFlush(a);
    }

    /**
     * A fresh milestone/requirement on an already-instantiated case, with an
     * agreement row landed directly in {@code status} -- for
     * {@code AgreementScopingTest} and {@code AgreementAudienceFilterTest} (Task
     * 6), which only need a row in a given state to assert visibility against, not
     * a row that arrived there through the real lifecycle. Bypasses
     * {@code AgreementService} entirely, the same "build the row directly" shape
     * {@code DocumentAudienceTest}/{@code PortalVisibilityTest} use for
     * {@code Document}.
     */
    public Agreement agreementRowInStatus(UUID tenant, UUID caseId, AgreementStatus status) {
        Case c = cases.findById(caseId).orElseThrow();
        Milestone m = journey.newMilestone(tenant, c);
        Requirement r = journey.newRequirement(tenant, c, m);
        Agreement a = draftAgreementRowFor(tenant, caseId, r.getId());
        a.setStatus(status);
        if (status == AgreementStatus.SIGNED) {
            a.setSignedAt(Instant.now());
        }
        if (status == AgreementStatus.CANCELLED) {
            a.setCancelReason("Fixture cancellation");
        }
        return agreements.saveAndFlush(a);
    }
}
