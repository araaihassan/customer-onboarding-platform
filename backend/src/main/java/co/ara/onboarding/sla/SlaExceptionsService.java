package co.ara.onboarding.sla;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.document.DocumentRequestRepository;
import co.ara.onboarding.document.DocumentRequestStatus;
import co.ara.onboarding.identity.ReportingLineDirectory;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The war room feed (spec 8, 9.2). Scope is the viewer's own sla.view scope: clocks, pauses and
 * escalations are read through AuthorizedQuery under SLA_VIEW, a clock or escalation whose case the
 * viewer cannot also read under case.view is dropped, and the summary strip is counted over exactly
 * that set -- there is deliberately no tenant-wide aggregate here. Every number is SlaClockReader's.
 */
@Service
public class SlaExceptionsService {

    private static final Duration AUTO_ESCALATED_WINDOW = Duration.ofDays(7);

    private final AuthorizedQuery authorizedQuery;
    private final SlaClockRepository clocks;
    private final EscalationRepository escalations;
    private final CaseRepository cases;
    private final StageRepository stages;
    private final CustomerRepository customers;
    private final DocumentRequestRepository documentRequests;
    private final SlaClockService clockService;
    private final ReportingLineDirectory people;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public SlaExceptionsService(AuthorizedQuery authorizedQuery, SlaClockRepository clocks,
                                EscalationRepository escalations, CaseRepository cases, StageRepository stages,
                                CustomerRepository customers, DocumentRequestRepository documentRequests,
                                SlaClockService clockService, ReportingLineDirectory people,
                                BusinessCalendar calendar, Clock clock) {
        this.authorizedQuery = authorizedQuery; this.clocks = clocks; this.escalations = escalations;
        this.cases = cases; this.stages = stages; this.customers = customers;
        this.documentRequests = documentRequests; this.clockService = clockService; this.people = people;
        this.calendar = calendar; this.clock = clock;
    }

    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(readOnly = true)
    public ExceptionsView exceptions() {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(AUTO_ESCALATED_WINDOW);

        List<SlaClock> open = authorizedQuery.findAll(clocks, SlaClock.class, PermissionKeys.SLA_VIEW,
                (r, q, cb) -> cb.isNull(r.get("stoppedAt")), Pageable.unpaged()).getContent();
        Set<UUID> clockCaseIds = open.stream().map(SlaClock::getCaseId).collect(Collectors.toSet());

        // Escalations on a clock's case (any age, for the card history) or raised in the last 7 days
        // (for the strip). Still SLA_VIEW-scoped; narrowed again to visible cases below.
        List<Escalation> allEscalations = authorizedQuery.findAll(escalations, Escalation.class,
                PermissionKeys.SLA_VIEW, (r, q, cb) -> clockCaseIds.isEmpty()
                        ? cb.greaterThanOrEqualTo(r.<Instant>get("escalatedAt"), cutoff)
                        : cb.or(r.get("caseId").in(clockCaseIds),
                                cb.greaterThanOrEqualTo(r.<Instant>get("escalatedAt"), cutoff)),
                Pageable.unpaged()).getContent();

        Set<UUID> caseIds = new HashSet<>(clockCaseIds);
        allEscalations.forEach(e -> caseIds.add(e.getCaseId()));
        Map<UUID, Case> visibleCases = caseIds.isEmpty() ? Map.of() : authorizedQuery.findAll(cases, Case.class,
                        PermissionKeys.CASE_VIEW, (r, q, cb) -> r.get("id").in(caseIds), Pageable.unpaged())
                .getContent().stream().collect(Collectors.toMap(Case::getId, Function.identity()));

        List<SlaClock> visibleClocks = open.stream().filter(c -> visibleCases.containsKey(c.getCaseId())).toList();
        List<Escalation> visibleEscalations = allEscalations.stream()
                .filter(e -> visibleCases.containsKey(e.getCaseId()))
                .sorted(Comparator.comparing(Escalation::getEscalatedAt).reversed()).toList();

        Map<UUID, SlaClockView> views = clockService.views(visibleClocks);

        // No workflow.view / customer.view grant yields an empty map (the predicate collapses), so a
        // name is simply null rather than the nested-lookup 404 the CLAUDE.md Tests section warns of.
        Set<UUID> stageIds = visibleClocks.stream().map(SlaClock::getStageId).collect(Collectors.toSet());
        Map<UUID, String> stageNames = stageIds.isEmpty() ? Map.of() : authorizedQuery.findAll(stages, Stage.class,
                        PermissionKeys.WORKFLOW_VIEW, (r, q, cb) -> r.get("id").in(stageIds), Pageable.unpaged())
                .getContent().stream().collect(Collectors.toMap(Stage::getId, Stage::getName));
        Set<UUID> customerIds = visibleClocks.stream().map(c -> visibleCases.get(c.getCaseId()).getCustomerId())
                .collect(Collectors.toSet());
        Map<UUID, String> customerNames = customerIds.isEmpty() ? Map.of() : authorizedQuery.findAll(customers,
                        Customer.class, PermissionKeys.CUSTOMER_VIEW, (r, q, cb) -> r.get("id").in(customerIds),
                        Pageable.unpaged())
                .getContent().stream().collect(Collectors.toMap(Customer::getId, Customer::getDisplayName));

        // A grouped id query, not a finder (the finder rule binds on find*), keyed on cases the viewer
        // already resolved above; it returns case ids only and nothing from the requests themselves.
        Set<UUID> withOpenRequests = visibleClocks.isEmpty() ? Set.of() : new HashSet<>(documentRequests
                .caseIdsWithRequestsIn(visibleCases.keySet(), DocumentRequestStatus.OPEN));

        Map<UUID, Optional<String>> names = new HashMap<>();
        Function<UUID, String> nameOf = id -> id == null ? null : names.computeIfAbsent(id,
                k -> people.activeUser(k).map(ReportingLineDirectory.Recipient::fullName)).orElse(null);

        Map<UUID, List<ExceptionsView.EscalationNote>> notesByCase = new HashMap<>();
        for (Escalation e : visibleEscalations) {
            notesByCase.computeIfAbsent(e.getCaseId(), k -> new ArrayList<>()).add(new ExceptionsView.EscalationNote(
                    e.getSubjectType(), e.getSubjectId(), e.getRoute(), e.getEscalatedToUserId(),
                    nameOf.apply(e.getEscalatedToUserId()), nameOf.apply(e.getLateUserId()),
                    e.getDueDateAtEscalation(), e.getOverdueDays(), e.getEscalatedAt()));
        }

        List<ExceptionsView.Card> breached = new ArrayList<>();
        List<ExceptionsView.Card> dueToday = new ArrayList<>();
        List<ExceptionsView.Card> watch = new ArrayList<>();
        int paused = 0;
        for (SlaClock c : visibleClocks) {
            SlaClockView v = views.get(c.getId());
            if (v.state() == SlaClockState.PAUSED) paused++;
            boolean isBreached = v.state() == SlaClockState.BREACHED;
            if (!isBreached && !v.dueToday() && !v.atRisk()) continue;
            Case k = visibleCases.get(c.getCaseId());
            ExceptionsView.Card card = new ExceptionsView.Card(k.getId(), k.getName(), k.getCustomerId(),
                    customerNames.get(k.getCustomerId()), stageNames.get(c.getStageId()), k.getOwnerUserId(),
                    nameOf.apply(k.getOwnerUserId()), v, withOpenRequests.contains(k.getId()),
                    notesByCase.getOrDefault(k.getId(), List.of()));
            if (isBreached) breached.add(card);
            else if (v.dueToday()) dueToday.add(card);
            else watch.add(card);
        }
        breached.sort(Comparator.comparingDouble(
                (ExceptionsView.Card c) -> c.clock().elapsedDays() - c.clock().targetDays()).reversed());
        dueToday.sort(Comparator.comparingDouble(c -> c.clock().remainingDays()));
        watch.sort(Comparator.comparingDouble(c -> c.clock().remainingDays()));

        int autoEscalated = (int) visibleEscalations.stream()
                .filter(e -> !e.getEscalatedAt().isBefore(cutoff)).count();
        return new ExceptionsView(new ExceptionsView.Summary(breached.size(), dueToday.size(), paused, autoEscalated),
                breached, dueToday, watch, calendar.name());
    }
}
