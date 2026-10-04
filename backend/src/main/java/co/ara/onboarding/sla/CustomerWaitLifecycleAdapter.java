package co.ara.onboarding.sla;

import co.ara.onboarding.document.CustomerWaitLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Implements document's CustomerWaitLifecycle port; delegates to {@link SlaClockWriter}. */
@Component
public class CustomerWaitLifecycleAdapter implements CustomerWaitLifecycle {
    private final SlaClockWriter writer;

    public CustomerWaitLifecycleAdapter(SlaClockWriter writer) { this.writer = writer; }

    @Override public void requestOpened(UUID caseId, Instant at) {
        writer.openPause(caseId, PauseReason.OPEN_DOCUMENT_REQUEST, at);
    }

    /** One interval for any number of open requests (spec 5 rule 3): close only when none remain. */
    @Override public void requestClosed(UUID caseId, Instant at) {
        if (writer.openRequestCount(caseId) == 0) {
            writer.closePause(caseId, PauseReason.OPEN_DOCUMENT_REQUEST, at);
        }
    }
}
