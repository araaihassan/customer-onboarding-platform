package co.ara.onboarding.journey;

import java.time.Instant;
import java.util.UUID;

/**
 * The SLA clock's view of a case's lifecycle (spec 3.2). Declared here, implemented by sla, so
 * journey never imports sla (invariant 3). Called only where currentStageId changes -- CaseEngine
 * and MilestoneService.reopen -- and from hold/resume, always inside the caller's transaction
 * (invariants 1, 2).
 */
public interface SlaClockLifecycle {
    void stageEntered(UUID caseId, UUID stageId, Instant at);
    void stageExited(UUID caseId, Instant at);
    void held(UUID caseId, Instant at);
    void resumed(UUID caseId, Instant at);
}
