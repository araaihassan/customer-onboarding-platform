package co.ara.onboarding.agreement;

/** The request body for a write that needs nothing beyond the optimistic-lock guard -- {@code send}. */
public record LockVersionRequest(long lockVersion) {}
