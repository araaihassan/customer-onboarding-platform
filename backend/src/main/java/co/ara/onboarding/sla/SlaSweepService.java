package co.ara.onboarding.sla;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.CaseStatus;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.MilestoneStatus;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.task.Task;
import co.ara.onboarding.task.TaskRepository;
import co.ara.onboarding.task.TaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
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
    private final BusinessCalendar calendar;
    private final SlaPolicyReader policies;
    private final TaskRepository tasks;
    private final MilestoneRepository milestones;
    private final CaseRepository caseRepository;
    private final RecipientResolver resolver;
    private final EscalationWriter writer;

    public SlaSweepService(AuthorizedQuery authorizedQuery, SlaClockRepository clocks, SlaPauseRepository pauses,
                           SlaClockReader reader, AuditRecorder audit, Clock clock, BusinessCalendar calendar,
                           SlaPolicyReader policies, TaskRepository tasks, MilestoneRepository milestones,
                           CaseRepository caseRepository, RecipientResolver resolver, EscalationWriter writer) {
        this.authorizedQuery = authorizedQuery; this.clocks = clocks; this.pauses = pauses;
        this.reader = reader; this.audit = audit; this.clock = clock; this.calendar = calendar;
        this.policies = policies; this.tasks = tasks; this.milestones = milestones;
        this.caseRepository = caseRepository; this.resolver = resolver; this.writer = writer;
    }

    /** The escalation-writing steps in spec order; later tasks extend it. */
    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public void sweep() {
        stampBreaches();
        escalateOverdue();
    }

    private record Candidate(EscalationSubject type, UUID id, UUID caseId, UUID latePerson, LocalDate dueDate) {}

    /**
     * Spec 6.1 steps 2-3, invariant 5. Raises one escalation per overdue task, milestone or breached
     * clock on an ACTIVE case, judged against today's date in the tenant's zone and the policy's
     * overdue-day threshold. The unique key (subject + due date) plus ON CONFLICT DO NOTHING is what
     * makes it exactly-once, also across concurrent sweeps; audit and the returned list cover only the
     * rows this call actually inserted. A re-dated subject has a new due date and so may escalate again.
     */
    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RaisedEscalation> escalateOverdue() {
        LocalDate today = calendar.today();
        SlaPolicy policy = policies.current();
        Instant now = Instant.now(clock);
        List<Candidate> candidates = new ArrayList<>();

        Specification<Task> overdueTasks = (r, q, cb) -> cb.and(
                r.get("status").in(TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.WAITING),
                cb.isNotNull(r.get("dueDate")), cb.lessThan(r.<LocalDate>get("dueDate"), today));
        for (Task t : authorizedQuery.findAll(tasks, Task.class, PermissionKeys.TASK_VIEW, overdueTasks,
                Pageable.unpaged(Sort.by("id")))) {
            candidates.add(new Candidate(EscalationSubject.TASK, t.getId(), t.getCaseId(), t.getAssigneeId(),
                    t.getDueDate()));
        }
        Specification<Milestone> overdueMilestones = (r, q, cb) -> cb.and(
                r.get("status").in(MilestoneStatus.PENDING, MilestoneStatus.ACTIVE, MilestoneStatus.BLOCKED),
                cb.isNotNull(r.get("dueDate")), cb.lessThan(r.<LocalDate>get("dueDate"), today));
        for (Milestone m : authorizedQuery.findAll(milestones, Milestone.class, PermissionKeys.CASE_VIEW,
                overdueMilestones, Pageable.unpaged(Sort.by("id")))) {
            candidates.add(new Candidate(EscalationSubject.MILESTONE, m.getId(), m.getCaseId(), m.getOwnerUserId(),
                    m.getDueDate()));
        }
        Specification<SlaClock> breached = (r, q, cb) ->
                cb.and(cb.isNull(r.get("stoppedAt")), cb.isNotNull(r.get("breachedAt")));
        for (SlaClock c : authorizedQuery.findAll(clocks, SlaClock.class, PermissionKeys.SLA_VIEW, breached,
                Pageable.unpaged(Sort.by("id")))) {
            candidates.add(new Candidate(EscalationSubject.SLA_CLOCK, c.getId(), c.getCaseId(), null,
                    calendar.localDate(c.getBreachedAt())));
        }
        if (candidates.isEmpty()) return List.of();

        Set<UUID> caseIds = new HashSet<>();
        candidates.forEach(c -> caseIds.add(c.caseId()));
        Map<UUID, Case> casesById = authorizedQuery.findAll(caseRepository, Case.class, PermissionKeys.CASE_VIEW,
                (r, q, cb) -> r.get("id").in(caseIds), Pageable.unpaged())
                .stream().collect(Collectors.toMap(Case::getId, c -> c));

        List<RaisedEscalation> raised = new ArrayList<>();
        for (Candidate c : candidates) {
            Case kase = casesById.get(c.caseId());
            if (kase == null || kase.getStatus() != CaseStatus.ACTIVE) continue;
            int overdueDays = calendar.businessDaysBetween(c.dueDate(), today);
            if (!today.isAfter(c.dueDate()) || overdueDays < policy.escalateAfterOverdueDays()) continue;
            UUID late = c.latePerson() != null ? c.latePerson() : kase.getOwnerUserId();
            RecipientResolver.Resolution resolution = resolver.resolve(late);
            // Audit follows the conditional insert: only a row this call wrote is recorded or returned.
            // Nothing audited depends on the escalation id existing first, unlike the cause-before-effect
            // sites, so recording after the write cannot scramble a sequence.
            var id = writer.insert(c.type(), c.id(), c.caseId(), late, resolution, c.dueDate(), overdueDays, now);
            if (id.isEmpty()) continue;
            audit.record(AuditActions.ESCALATION_RAISED, "escalation", id.get(),
                    "Escalated overdue " + c.type().name().toLowerCase() + " to " + resolution.route(),
                    Map.of("caseId", c.caseId().toString(), "subjectType", c.type().name(),
                            "subjectId", c.id().toString(), "route", resolution.route().name(),
                            "overdueDays", overdueDays));
            raised.add(new RaisedEscalation(id.get(), c.type(), c.id(), c.caseId(), kase.getName(),
                    kase.getCustomerId(), late, resolution, overdueDays));
        }
        return raised;
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
                openUnstamped, Pageable.unpaged(Sort.by("id"))).getContent();
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
