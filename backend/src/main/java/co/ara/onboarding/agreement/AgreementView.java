package co.ara.onboarding.agreement;

import co.ara.onboarding.workflow.AgreementRecordMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code status} (stored) and {@code displayStatus} (derived) are both carried
 * deliberately: the UI decides actions from {@code status} and renders the
 * chip from {@code displayStatus}. {@code customerName} is resolved
 * best-effort (see {@code AgreementService}'s own javadoc) and is {@code null}
 * when the viewer holds no {@code customer.view}.
 */
public record AgreementView(UUID id, UUID caseId, UUID requirementId, UUID customerId, String customerName,
        String name, AgreementRecordMode recordMode, AgreementStatus status, AgreementDisplayStatus displayStatus,
        LocalDate effectiveDate, LocalDate expiresAt, LocalDate renewalDate, Integer noticePeriodDays,
        UUID ownerUserId, UUID documentId, UUID lastEditedBy, UUID replacesAgreementId, String cancelReason,
        Instant signedAt, SignatureProviderKind signatureProvider, int latestVersionNumber, long lockVersion) {}
