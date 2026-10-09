package co.ara.onboarding.notification;

/** The deadlines a tenant sets lead times for (6B spec 4.4). Due dates count business days; expiries calendar days. */
public enum HorizonKind {
    TASK_DUE(true), MILESTONE_DUE(true), DOCUMENT_REQUEST_DUE(true),
    DOCUMENT_EXPIRY(false), AGREEMENT_EXPIRY(false), AGREEMENT_RENEWAL(false);

    public final boolean businessDays;

    HorizonKind(boolean businessDays) { this.businessDays = businessDays; }
}
