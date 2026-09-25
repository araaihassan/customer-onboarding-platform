package co.ara.onboarding.document;

/**
 * How broadly a document is visible -- one of the two visibility axes (QA Q9 and
 * its 2026-08-29 amendment); {@code target_department_id}/{@code target_contact_label}
 * on {@link Document} carry the other axis, which group. Mirrors document_tier_ck.
 */
public enum VisibilityTier {
    COMPANY_SHARED, CONTACT_ONLY, SENSITIVE
}
