package co.ara.onboarding.agreement;

import co.ara.onboarding.workflow.AgreementRecordMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a portal contact sees of an agreement sent to their customer -- deliberately narrower than
 * {@link AgreementView}: no reviewer, review reason, recorder, cancel reason, owner, contact/user
 * ids or lock version. A signatory is a display role and a signed state, nothing that identifies
 * an internal user.
 */
public record PortalAgreementView(UUID id, UUID caseId, String name, AgreementRecordMode recordMode,
        AgreementDisplayStatus displayStatus, LocalDate effectiveDate, LocalDate expiresAt, LocalDate renewalDate,
        Integer noticePeriodDays, int sentVersionNumber, String sentContentSha256, UUID documentId,
        List<PortalSignatory> signatories, Instant signedAt) {

    public record PortalSignatory(String displayRole, boolean signed, LocalDate signedOn) {}
}
