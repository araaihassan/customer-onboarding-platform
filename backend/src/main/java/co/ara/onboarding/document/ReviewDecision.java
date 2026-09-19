package co.ara.onboarding.document;

/**
 * The caller's input to {@link DocumentReviewService#review}. Deliberately NOT
 * the same enum as {@link ReviewStatus}: {@code PENDING} is a value a
 * {@link DocumentVersion} can be IN, never a value a reviewer DECIDES --
 * collapsing the two into one enum would let a caller send
 * {@code "PENDING"} as a "decision" with no sensible meaning at all.
 */
public enum ReviewDecision {
    APPROVED, REJECTED
}
