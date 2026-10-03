package co.ara.onboarding.sla;

/** The tenant's SLA policy (spec §4.1); defaults match V28's column defaults. */
public record SlaPolicy(double atRiskDays, int escalateAfterOverdueDays) {
    public static SlaPolicy defaults() { return new SlaPolicy(1.0, 1); }
}
