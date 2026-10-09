package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.task.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 6B spec 6.2. Candidates come from RLS-bound SQL; every notification is still gated per recipient
 * by RecipientAccess inside the pipeline (plan amendment 4). Gated sla.view, the job marker (plan
 * amendment 9). Every send carries a dedupe key, so repeat runs are free and a moved date re-arms.
 */
@Service
public class NotificationSweepService {

    private final DeadlineCandidates candidates;
    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;
    private final PolicyReader policies;
    private final BusinessCalendar calendar;
    private final DocumentRequestService documentRequests;

    NotificationSweepService(DeadlineCandidates candidates, NotificationPipeline pipeline, SubjectFacts facts,
                             PolicyReader policies, BusinessCalendar calendar,
                             DocumentRequestService documentRequests) {
        this.candidates = candidates;
        this.pipeline = pipeline;
        this.facts = facts;
        this.policies = policies;
        this.calendar = calendar;
        this.documentRequests = documentRequests;
    }

    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public void sweep() {
        LocalDate today = calendar.today();
        var policy = policies.current();
        taskOverdue(today);
        deadlines(today, policy);
        expiries(today, policy);
        autoReminders(policy);
    }

    /** Spec 6.2: policy-driven customer reminders; the only DocumentRequestService call the sweep makes. */
    void autoReminders(PolicyReader.Policy policy) {
        if (!policy.autoRemindEnabled()) return;
        LocalDate today = calendar.today();
        for (var r : candidates.remindableRequests(policy.max())) {
            Instant since = r.get("last_reminded_at") != null
                    ? ((java.sql.Timestamp) r.get("last_reminded_at")).toInstant()
                    : ((java.sql.Timestamp) r.get("requested_at")).toInstant();
            if (calendar.businessDaysBetween(calendar.localDate(since), today) < policy.intervalDays()) continue;
            documentRequests.remindAutomatically((UUID) r.get("id"));
        }
    }

    void taskOverdue(LocalDate today) {
        String slug = facts.tenantSlug();
        for (var t : candidates.openTasksDueBefore(today)) {
            var kase = facts.caseFacts(t.caseId());
            int late = calendar.businessDaysBetween(t.date(), today);
            String title = "Overdue: " + Text.clip(t.label(), 90);
            String due = "due " + t.date() + (late > 0 ? ", " + late + " business day(s) ago." : ".");
            var draft = new NotificationPipeline.Draft(NotificationType.TASK_OVERDUE, "task", t.id(), kase.id(),
                    title, kase.name() + ": " + due,
                    Links.caseLink(slug, kase.customerId(), kase.id()), Tone.WARN,
                    "TASK_OVERDUE:" + t.id() + ":" + t.date())
                    .orWithoutCase(title, "Task " + due, Links.workLink(slug));
            pipeline.deliver(draft, List.of(t.ownerUserId()), null,
                    new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, t.id()));
        }
    }

    void deadlines(LocalDate today, PolicyReader.Policy policy) {
        for (var t : candidates.openTasksDueOnOrAfter(today)) {
            remind(HorizonKind.TASK_DUE, "task", t, today, policy,
                    new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, t.id()));
        }
        for (var m : candidates.openMilestonesDueOnOrAfter(today)) {
            remind(HorizonKind.MILESTONE_DUE, "milestone", m, today, policy,
                    new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, m.caseId()));
        }
        for (var r : candidates.openRequestsDue()) {
            LocalDate due = calendar.localDate(((java.sql.Timestamp) r.get("due_at")).toInstant());
            if (due.isBefore(today)) continue;
            var dated = new DeadlineCandidates.Dated((UUID) r.get("id"), (UUID) r.get("case_id"),
                    (UUID) r.get("requested_by"), due, DocumentNotifications.humanise((String) r.get("category")));
            remind(HorizonKind.DOCUMENT_REQUEST_DUE, "document_request", dated, today, policy,
                    new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, dated.caseId()));
        }
    }

    /** The smallest matching lead is sent; larger matching leads are consumed so they never fire late. */
    private void remind(HorizonKind kind, String subjectType, DeadlineCandidates.Dated item, LocalDate today,
                        PolicyReader.Policy policy, NotificationPipeline.Visibility visibility) {
        int away = kind.businessDays ? calendar.businessDaysBetween(today, item.date())
                : (int) ChronoUnit.DAYS.between(today, item.date());
        List<Integer> matching = policy.horizons().get(kind).stream().filter(lead -> away <= lead).sorted().toList();
        if (matching.isEmpty()) return;
        var kase = facts.caseFacts(item.caseId());
        String when = away == 0 ? "today" : "in " + away + (kind.businessDays ? " business day(s)" : " day(s)");
        for (int i = 0; i < matching.size(); i++) {
            int lead = matching.get(i);
            String title = Text.clip(item.label(), 90) + " is due " + when;
            // Milestones and document requests are gated on the case itself; only a task's deadline can
            // reach someone who cannot view the case, and they are pointed at their own work instead.
            var draft = new NotificationPipeline.Draft(NotificationType.DEADLINE_APPROACHING, subjectType, item.id(),
                    kase.id(), title, kase.name() + " (" + kase.customerName() + "): due " + item.date() + ".",
                    Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.WARN,
                    "DEADLINE:" + kind + ":" + item.id() + ":" + item.date() + ":" + lead)
                    .orWithoutCase(title, "Due " + item.date() + ".", Links.workLink(facts.tenantSlug()));
            if (i == 0) pipeline.deliver(draft, List.of(item.ownerUserId()), null, visibility);
            else pipeline.consume(draft, item.ownerUserId(), visibility);
        }
    }

    /** Document expiry and agreement expiry/renewal (PRD section 9), in calendar days. */
    void expiries(LocalDate today, PolicyReader.Policy policy) {
        for (var d : candidates.liveDocumentsExpiring()) {
            LocalDate date = calendar.localDate(((java.sql.Timestamp) d.get("expires_at")).toInstant());
            UUID id = (UUID) d.get("id");
            expiry(HorizonKind.DOCUMENT_EXPIRY, "document", id, (UUID) d.get("case_id"),
                    List.of((UUID) d.get("owner_user_id")), date,
                    "\"" + Text.clip((String) d.get("name"), 80) + "\" expires", today, policy,
                    new NotificationPipeline.Visibility(PermissionKeys.DOCUMENT_VIEW, Document.class, id));
        }
        for (var a : candidates.liveAgreementsWithDates()) {
            UUID id = (UUID) a.get("id");
            UUID caseId = (UUID) a.get("case_id");
            var owners = new ArrayList<UUID>();
            if (a.get("owner_user_id") != null) owners.add((UUID) a.get("owner_user_id"));
            UUID caseOwner = facts.caseFacts(caseId).ownerUserId();
            if (caseOwner != null && !owners.contains(caseOwner)) owners.add(caseOwner);
            var visibility = new NotificationPipeline.Visibility(PermissionKeys.AGREEMENT_VIEW, Agreement.class, id);
            String name = Text.clip((String) a.get("name"), 80);
            if (a.get("expires_at") != null) {
                expiry(HorizonKind.AGREEMENT_EXPIRY, "agreement", id, caseId, owners, toLocalDate(a.get("expires_at")),
                        name + " expires", today, policy, visibility);
            }
            if (a.get("renewal_date") != null) {
                int notice = a.get("notice_period_days") == null ? 0 : ((Number) a.get("notice_period_days")).intValue();
                LocalDate decision = toLocalDate(a.get("renewal_date")).minusDays(notice);
                expiry(HorizonKind.AGREEMENT_RENEWAL, "agreement", id, caseId, owners, decision,
                        "Renewal decision due for " + name, today, policy, visibility);
            }
        }
    }

    private static LocalDate toLocalDate(Object o) {
        return o instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) o;
    }

    private void expiry(HorizonKind kind, String subjectType, UUID id, UUID caseId, List<UUID> recipients,
                        LocalDate date, String what, LocalDate today, PolicyReader.Policy policy,
                        NotificationPipeline.Visibility visibility) {
        long away = ChronoUnit.DAYS.between(today, date);
        if (away < 0) return;
        List<Integer> matching = policy.horizons().get(kind).stream().filter(lead -> away <= lead).sorted().toList();
        if (matching.isEmpty()) return;
        var kase = facts.caseFacts(caseId);
        String slug = facts.tenantSlug();
        // Gated document.view / agreement.view, neither of which implies case.view.
        String ownScreen = "agreement".equals(subjectType) ? Links.agreementsLink(slug) : Links.documentsLink(slug);
        for (int i = 0; i < matching.size(); i++) {
            String title = what + (away == 0 ? " today" : " in " + away + " day(s)");
            var draft = new NotificationPipeline.Draft(NotificationType.EXPIRY_RENEWAL, subjectType, id, kase.id(),
                    title, kase.name() + " (" + kase.customerName() + "): " + date + ".",
                    Links.caseLink(slug, kase.customerId(), kase.id()), Tone.WARN,
                    "EXPIRY:" + kind + ":" + id + ":" + date + ":" + matching.get(i))
                    .orWithoutCase(title, "On " + date + ".", ownScreen);
            if (i == 0) pipeline.deliver(draft, recipients, null, visibility);
            else for (UUID r : recipients) pipeline.consume(draft, r, visibility);
        }
    }
}
