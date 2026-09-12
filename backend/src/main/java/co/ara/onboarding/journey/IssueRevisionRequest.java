package co.ara.onboarding.journey;

/**
 * The issuer's own note on why this schedule revision is being sent -- distinct
 * from {@link PlanRevision#getDecisionNote()}, which is the customer's own
 * reasoning recorded when they decide it (Task 25). Optional: a reissue after a
 * milestone reshuffle may need no explanation beyond the dated snapshot itself.
 */
public record IssueRevisionRequest(String note) {}
