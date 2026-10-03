package co.ara.onboarding.sla;

import co.ara.onboarding.document.DocumentRequestRepository;
import co.ara.onboarding.document.DocumentRequestStatus;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.CaseStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Every write to sla_clock/sla_pause (spec 5). Runs only inside a caller's transaction
 * (MANDATORY) on a case id that caller already resolved under its own gate -- which is why it
 * carries a FINDER_RULE_EXCLUSIONS entry and reads repositories directly (the
 * TaskLifecycleAdapter precedent). Both lifecycle adapters delegate here and inject nothing else.
 */
@Component
class SlaClockWriter {

    private final SlaClockRepository clocks;
    private final SlaPauseRepository pauses;
    private final StageRepository stages;
    private final CaseRepository cases;
    private final DocumentRequestRepository requests;
    private final SlaClockReader reader;
    private final EntityManager em;

    SlaClockWriter(SlaClockRepository clocks, SlaPauseRepository pauses, StageRepository stages,
                   CaseRepository cases, DocumentRequestRepository requests, SlaClockReader reader, EntityManager em) {
        this.em = em;
        this.clocks = clocks; this.pauses = pauses; this.stages = stages;
        this.cases = cases; this.requests = requests; this.reader = reader;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void start(UUID caseId, UUID stageId, Instant at) {
        stopOpen(caseId, at);   // defensive; one open clock per case is also a unique index
        Stage stage = stages.findById(stageId).orElseThrow();
        if (stage.getSlaDays() == null || stage.getSlaDays() <= 0) return;
        SlaClock c = new SlaClock();
        c.setId(Uuid7.generate());
        c.setTenantId(TenantContext.getRequired());
        c.setCaseId(caseId);
        c.setStageId(stageId);
        c.setTargetDays(stage.getSlaDays());
        c.setPauseEligible(stage.isPausesOnCustomer());
        c.setStartedAt(at);
        clocks.saveAndFlush(c);
        // A stage entered while the case is already waiting starts paused.
        Case kase = cases.findById(caseId).orElseThrow();
        if (kase.getStatus() == CaseStatus.ON_HOLD) openPause(caseId, PauseReason.CASE_HOLD, at);
        if (openRequestCount(caseId) > 0) openPause(caseId, PauseReason.OPEN_DOCUMENT_REQUEST, at);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void stopOpen(UUID caseId, Instant at) {
        clocks.findByCaseIdAndStoppedAtIsNull(caseId).ifPresent(c -> {
            // Lock and reload: the sweep stamps breached_at without the case lock, so the snapshot
            // loaded above may be stale. Under the row lock the outcome sees any committed stamp.
            em.refresh(c, LockModeType.PESSIMISTIC_WRITE);
            if (c.getStoppedAt() != null) return;
            for (SlaPause p : pauses.findByClockIdAndEndedAtIsNull(c.getId())) {
                p.setEndedAt(at);
                pauses.save(p);
            }
            double elapsed = reader.elapsed(c, pauses.findByClockId(c.getId()), at);
            c.setStoppedAt(at);
            c.setOutcome(c.getBreachedAt() != null || reader.exhausted(elapsed, c.getTargetDays())
                    ? SlaClockOutcome.BREACHED : SlaClockOutcome.MET);
            clocks.saveAndFlush(c);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void openPause(UUID caseId, PauseReason reason, Instant at) {
        clocks.findByCaseIdAndStoppedAtIsNull(caseId).ifPresent(c -> {
            if (reason == PauseReason.OPEN_DOCUMENT_REQUEST && !c.isPauseEligible()) return;
            if (pauses.findByClockIdAndReasonAndEndedAtIsNull(c.getId(), reason).isPresent()) return;
            SlaPause p = new SlaPause();
            p.setId(Uuid7.generate());
            p.setTenantId(c.getTenantId());
            p.setClockId(c.getId());
            p.setReason(reason);
            p.setStartedAt(at);
            pauses.saveAndFlush(p);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void closePause(UUID caseId, PauseReason reason, Instant at) {
        clocks.findByCaseIdAndStoppedAtIsNull(caseId).ifPresent(c ->
                pauses.findByClockIdAndReasonAndEndedAtIsNull(c.getId(), reason).ifPresent(p -> {
                    p.setEndedAt(at);
                    pauses.saveAndFlush(p);
                }));
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    long openRequestCount(UUID caseId) {
        return requests.countByCaseIdAndStatus(caseId, DocumentRequestStatus.OPEN);
    }
}
