package co.ara.onboarding.document;

/** Mirrors document_category_ck exactly -- a new category is a migration, not a Java-only change. */
public enum DocumentCategory {
    CONTRACT, AGREEMENT, NDA, COMPANY_REGISTRATION, TAX, KYC,
    TECHNICAL, CERTIFICATE, INVOICE, OTHER
}
