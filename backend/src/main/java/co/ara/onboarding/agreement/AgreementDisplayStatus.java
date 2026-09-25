package co.ara.onboarding.agreement;

import java.time.LocalDate;

/**
 * The status the UI renders, distinct from {@link AgreementStatus} -- the
 * status actually stored. EXPIRED is never written to the database (spec
 * section 4.2/5.7 and {@code agreement_status_ck}); it is derived here, on
 * read, from {@code expires_at} against today. Every other value is a
 * one-to-one passthrough of the stored status.
 *
 * {@code AgreementView} carries both: the UI decides actions from {@code
 * status} (the stored one -- a cancel button, a submit button) and renders
 * the chip from {@code displayStatus}.
 */
public enum AgreementDisplayStatus {
    DRAFT, UNDER_REVIEW, APPROVED, SENT, AWAITING_SIGNATURE, SIGNED, EXPIRED, CANCELLED;

    /** Spec 5.7: EXPIRED is derived -- stored SIGNED with expires_at strictly before today (UTC). */
    public static AgreementDisplayStatus of(AgreementStatus stored, LocalDate expiresAt, LocalDate today) {
        if (stored == AgreementStatus.SIGNED && expiresAt != null && expiresAt.isBefore(today)) return EXPIRED;
        return valueOf(stored.name());
    }
}
