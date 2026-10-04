package co.ara.onboarding.sla;

import co.ara.onboarding.journey.SlaClockLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Implements journey's SlaClockLifecycle port; delegates every call to {@link SlaClockWriter}. */
@Component
public class SlaClockLifecycleAdapter implements SlaClockLifecycle {
    private final SlaClockWriter writer;

    public SlaClockLifecycleAdapter(SlaClockWriter writer) { this.writer = writer; }

    @Override public void stageEntered(UUID caseId, UUID stageId, Instant at) { writer.start(caseId, stageId, at); }
    @Override public void stageExited(UUID caseId, Instant at) { writer.stopOpen(caseId, at); }
    @Override public void held(UUID caseId, Instant at) { writer.openPause(caseId, PauseReason.CASE_HOLD, at); }
    @Override public void resumed(UUID caseId, Instant at) { writer.closePause(caseId, PauseReason.CASE_HOLD, at); }
}
