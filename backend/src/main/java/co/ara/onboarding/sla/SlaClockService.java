package co.ara.onboarding.sla;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.identity.ReportingLineDirectory;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Reads of a case's SLA clock (spec §8). Every read goes through AuthorizedQuery under sla.view. */
@Service
public class SlaClockService {

    private final AuthorizedQuery authorizedQuery;
    private final CaseRepository cases;
    private final SlaClockRepository clocks;
    private final SlaPauseRepository pauses;
    private final EscalationRepository escalations;
    private final SlaClockReader reader;
    private final SlaPolicyReader policies;
    private final ReportingLineDirectory people;
    private final Clock clock;

    public SlaClockService(AuthorizedQuery authorizedQuery, CaseRepository cases, SlaClockRepository clocks,
                           SlaPauseRepository pauses, EscalationRepository escalations, SlaClockReader reader,
                           SlaPolicyReader policies, ReportingLineDirectory people, Clock clock) {
        this.authorizedQuery = authorizedQuery; this.cases = cases; this.clocks = clocks; this.pauses = pauses;
        this.escalations = escalations; this.reader = reader; this.policies = policies; this.people = people;
        this.clock = clock;
    }

    /**
     * The case's most recent clock (the open one if any). 404 when the case is out of scope, in
     * another tenant, or has never had a clock. The gate is sla.view alone (the gate is any-of, so
     * naming case.view too would admit a case reader with no SLA grant); the case itself still
     * resolves under case.view.
     */
    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(readOnly = true)
    public SlaClockView forCase(UUID caseId) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, caseId);
        Specification<SlaClock> ofCase = (root, q, cb) -> cb.equal(root.get("caseId"), c.getId());
        SlaClock latest = authorizedQuery.findAll(clocks, SlaClock.class, PermissionKeys.SLA_VIEW, ofCase,
                        PageRequest.of(0, 1, Sort.by("startedAt").descending()))
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException("Not found"));
        return views(List.of(latest)).get(latest.getId());
    }

    /** Views for clocks the caller already read through AuthorizedQuery; keyed by clock id, input order. */
    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(readOnly = true)
    public Map<UUID, SlaClockView> views(List<SlaClock> list) {
        if (list.isEmpty()) return Map.of();
        Instant now = Instant.now(clock);
        SlaPolicy policy = policies.current();
        Set<UUID> clockIds = list.stream().map(SlaClock::getId).collect(Collectors.toSet());
        Set<UUID> caseIds = list.stream().map(SlaClock::getCaseId).collect(Collectors.toSet());
        Specification<SlaPause> ofClocks = (root, q, cb) -> root.get("clockId").in(clockIds);
        Map<UUID, List<SlaPause>> pausesByClock = authorizedQuery
                .findAll(pauses, SlaPause.class, PermissionKeys.SLA_VIEW, ofClocks, Pageable.unpaged())
                .stream().collect(Collectors.groupingBy(SlaPause::getClockId));
        Specification<Escalation> clockEscalations = (root, q, cb) -> cb.and(
                root.get("caseId").in(caseIds),
                cb.equal(root.get("subjectType"), EscalationSubject.SLA_CLOCK));
        Map<UUID, Escalation> escalationByClock = authorizedQuery
                .findAll(escalations, Escalation.class, PermissionKeys.SLA_VIEW, clockEscalations, Pageable.unpaged())
                .stream().sorted(Comparator.comparing(Escalation::getEscalatedAt).reversed())
                .collect(Collectors.toMap(Escalation::getSubjectId, e -> e, (a, b) -> a));
        Map<UUID, SlaClockView> out = new LinkedHashMap<>();
        for (SlaClock c : list) {
            Escalation e = escalationByClock.get(c.getId());
            // A since-deactivated recipient resolves to no name: the view then carries the route only.
            SlaClockView.EscalatedTo to = e == null ? null : new SlaClockView.EscalatedTo(e.getRoute(),
                    e.getEscalatedToUserId(),
                    people.activeUser(e.getEscalatedToUserId())
                            .map(ReportingLineDirectory.Recipient::fullName).orElse(null),
                    e.getEscalatedAt());
            out.put(c.getId(), reader.view(c, pausesByClock.getOrDefault(c.getId(), List.of()), now, policy, to));
        }
        return out;
    }
}
