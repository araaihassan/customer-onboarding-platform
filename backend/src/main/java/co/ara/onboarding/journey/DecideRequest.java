package co.ara.onboarding.journey;

/** Shared by both decide endpoints -- see ApprovalService's own note on why they stay two methods. */
public record DecideRequest(boolean approve, String note) {}
