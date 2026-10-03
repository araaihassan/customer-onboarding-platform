package co.ara.onboarding.sla;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The periodic sweep for ONE tenant (spec 6). It runs inside {@code TenantJobRunner}, so the tenant
 * context, the SYSTEM principal and the request-scoped audit context are already bound; it never
 * loops tenants itself. Step 1 stamps breaches; later steps raise escalations and notifications.
 */
@Service
public class SlaSweepService {

    private final AuthorizedQuery authorizedQuery;
    private final SlaClockRepository clocks;
    private final SlaPauseRepository pauses;
    private final SlaClockReader reader;
    private final AuditRecorder audit;
    private final Clock clock;

    public SlaSweepService(AuthorizedQuery authorizedQuery, SlaClockRepository clocks, SlaPauseRepository pauses,
                           SlaClockReader reader, AuditRecorder audit, Clock clock) {
        this.authorizedQuery = authorizedQuery; this.clocks = clocks; this.pauses = pauses;
        this.reader = reader; this.audit = audit; this.clock = clock;
    }

    /** The escalation-writing steps in spec order; later tasks extend it. */
    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public void sweep() {
        stampBreaches();
    }

    /**
     * Stamps {@code breached_at} on every open, unstamped clock whose elapsed business time has
     * reached its target. Once only: a stamped or stopped clock is never read here, so a stamp is
     * never overwritten. Returns the number stamped.
     */
    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public int stampBreaches() {
        Instant now = Instant.now(clock);
        Specification<SlaClock> openUnstamped = (r, q, cb) ->
                cb.and(cb.isNull(r.get("stoppedAt")), cb.isNull(r.get("breachedAt")));
        List<SlaClock> open = authorizedQuery.findAll(clocks, SlaClock.class, PermissionKeys.SLA_VIEW,
                openUnstamped, Pageable.unpaged()).getContent();
        if (open.isEmpty()) return 0;
        Set<UUID> ids = open.stream().map(SlaClock::getId).collect(Collectors.toSet());
        Specification<SlaPause> ofClocks = (r, q, cb) -> r.get("clockId").in(ids);
        Map<UUID, List<SlaPause>> pausesByClock = authorizedQuery
                .findAll(pauses, SlaPause.class, PermissionKeys.CASE_VIEW, ofClocks, Pageable.unpaged())
                .stream().collect(Collectors.groupingBy(SlaPause::getClockId));
        int stamped = 0;
        for (SlaClock c : open) {
            double elapsed = reader.elapsed(c, pausesByClock.getOrDefault(c.getId(), List.of()), now);
            if (!reader.exhausted(elapsed, c.getTargetDays())) continue;
            // Conditional update: a clock stopped or stamped since it was read is left alone (0 rows),
            // so a lost race neither reopens it, overwrites a stamp, nor audits a breach that was not
            // stamped here. The audit row follows in the same transaction, only for a row we changed.
            if (clocks.stampBreach(c.getId(), now) != 1) continue;
            audit.record(AuditActions.SLA_BREACHED, "sla_clock", c.getId(), "SLA breached",
                    Map.of("caseId", c.getCaseId().toString(), "targetDays", c.getTargetDays()));
            stamped++;
        }
        return stamped;
    }
}
