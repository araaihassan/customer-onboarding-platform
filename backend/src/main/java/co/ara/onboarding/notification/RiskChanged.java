package co.ara.onboarding.notification;

import java.util.UUID;

/**
 * Published by the SLA sweep when a live clock first falls within the at-risk threshold, or breaches
 * (6B spec 6.1). Lives in {@code notification} so that {@code sla} depends on it and never the reverse
 * (plan amendment 2).
 */
public record RiskChanged(UUID clockId, UUID caseId, UUID stageId, State state, double remainingDays,
                          int targetDays) {
    public enum State { AT_RISK, BREACHED }
}
