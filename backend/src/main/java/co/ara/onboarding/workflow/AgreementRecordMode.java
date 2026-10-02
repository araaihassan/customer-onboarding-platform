package co.ara.onboarding.workflow;

/** QA Q13, spec section 4.1. Fixed per SIGNATURE requirement; an instantiated agreement copies it and never changes it. */
public enum AgreementRecordMode {
    FILE_BACKED, STRUCTURED_PLUS_FILE, STRUCTURED_ONLY;

    /** Whether an agreement in this mode must carry a file at submit, and a countersigned copy at its last signature. */
    public boolean includesFile() { return this != STRUCTURED_ONLY; }
}
