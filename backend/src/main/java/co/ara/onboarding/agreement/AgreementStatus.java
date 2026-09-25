package co.ara.onboarding.agreement;

/**
 * Spec section 4.2/5.7. EXPIRED is deliberately absent here and in the
 * {@code agreement_status_ck} database constraint -- it is derived on read from
 * {@code expiresAt}, never stored (the same reasoning
 * {@code journey.CaseStatus}/progress give for anything the engine can recompute).
 */
public enum AgreementStatus {
    DRAFT, UNDER_REVIEW, APPROVED, SENT, AWAITING_SIGNATURE, SIGNED, CANCELLED;

    /** Spec section 5.8: any status before SIGNED may be cancelled. */
    public boolean isCancellable() { return this != SIGNED && this != CANCELLED; }
}
