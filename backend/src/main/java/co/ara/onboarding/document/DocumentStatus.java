package co.ara.onboarding.document;

/** Mirrors document_status_ck. A document is retired, never deleted -- DELETE is denied at the database. */
public enum DocumentStatus {
    ACTIVE, RETIRED
}
