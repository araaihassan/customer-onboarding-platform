package co.ara.onboarding.audit;

import java.util.*;

/**
 * The finite registry of auditable actions. Every call to
 * {@link AuditRecorder#record} must use one of these constants; there is no
 * way to record an event whose action key was not seeded here. timelineVisible
 * marks which actions surface on the user-facing Activity Timeline
 * (sub-project 2) versus compliance-only events that stay in the audit log.
 */
public final class AuditActions {

    // Declared before the constants below: static fields initialize in
    // textual order, and of() writes into this map, so if BY_KEY were
    // declared after the constants it would still be null when the first
    // of("tenant.created", ...) call ran, throwing NullPointerException out
    // of the class's static initializer (ExceptionInInitializerError).
    // Verified empirically -- this is what happens with the field below the
    // constants.
    private static final Map<String, AuditAction> BY_KEY = new LinkedHashMap<>();

    // Which side of timelineVisible an action falls on is decided by WHOSE record
    // changed, not by how weighty the verb is. customer.deactivated is visible
    // because the customer's own record changed; user.deactivated is not, because a
    // vendor's internal staffing change is not the customer's business and putting
    // it on their timeline would leak the vendor's org changes. Everything below is
    // the tenant's own internal administration, so all of it is compliance-only.
    public static final AuditAction TENANT_CREATED            = of("tenant.created", false);
    public static final AuditAction USER_CREATED              = of("user.created", false);
    public static final AuditAction USER_UPDATED              = of("user.updated", false);
    public static final AuditAction USER_DEACTIVATED          = of("user.deactivated", false);
    public static final AuditAction USER_ROLE_ASSIGNED        = of("user.role_assigned", false);
    // Compliance-only for the same reason as its counterpart: whose record
    // changed is the tenant's own staff member, not the customer's. Revocation is
    // the half a reviewer actually needs -- "when did this account stop being
    // able to do that, and who decided" -- so a grant that could be added
    // traceably and removed silently was the wrong asymmetry, not a small gap.
    public static final AuditAction USER_ROLE_UNASSIGNED      = of("user.role_unassigned", false);
    public static final AuditAction TEAM_MEMBER_ADDED         = of("team.member_added", false);
    public static final AuditAction TEAM_MEMBER_REMOVED       = of("team.member_removed", false);
    public static final AuditAction DEPARTMENT_CREATED        = of("department.created", false);
    public static final AuditAction TEAM_CREATED              = of("team.created", false);
    public static final AuditAction ROLE_CREATED              = of("role.created", false);
    public static final AuditAction ROLE_UPDATED              = of("role.updated", false);
    public static final AuditAction ROLE_DISABLED             = of("role.disabled", false);
    public static final AuditAction LOGIN_SUCCEEDED           = of("auth.login_succeeded", false);
    public static final AuditAction LOGIN_FAILED              = of("auth.login_failed", false);
    public static final AuditAction REFRESH_REUSE_DETECTED    = of("auth.refresh_reuse_detected", false);
    public static final AuditAction CUSTOMER_CREATED          = of("customer.created", true);
    public static final AuditAction CUSTOMER_UPDATED          = of("customer.updated", true);
    public static final AuditAction CUSTOMER_DEACTIVATED      = of("customer.deactivated", true);
    // Timeline-visible for the same reason the customer actions are: a contact is
    // a customer-facing business record, and who was added to or removed from an
    // account is precisely what the Activity Timeline exists to show. Contrast the
    // identity and auth actions above, which are compliance evidence and stay
    // false. CONTACT_DEACTIVATED is separate from CONTACT_UPDATED because INACTIVE
    // is the only retirement a contact has — see CustomerContactService.update.
    public static final AuditAction CONTACT_CREATED           = of("contact.created", true);
    public static final AuditAction CONTACT_UPDATED           = of("contact.updated", true);
    public static final AuditAction CONTACT_DEACTIVATED       = of("contact.deactivated", true);
    public static final AuditAction INVITATION_SENT           = of("invitation.sent", true);
    public static final AuditAction INVITATION_ACCEPTED       = of("invitation.accepted", true);
    // Compliance-only: workflow authoring is tenant configuration, not the
    // customer's business -- contrast customer.* and contact.* above.
    public static final AuditAction WORKFLOW_TEMPLATE_CREATED     = of("workflow.template_created", false);
    public static final AuditAction WORKFLOW_TEMPLATE_DEACTIVATED = of("workflow.template_deactivated", false);
    public static final AuditAction WORKFLOW_DRAFT_SAVED          = of("workflow.draft_saved", false);
    public static final AuditAction WORKFLOW_DRAFT_DISCARDED      = of("workflow.draft_discarded", false);
    public static final AuditAction WORKFLOW_PUBLISHED            = of("workflow.published", false);
    // Timeline-visible for the same reason customer.* is: a case is the customer's
    // own record, so opening one, changing its ownership/participants/attributes is
    // exactly what the Activity Timeline exists to show.
    public static final AuditAction CASE_CREATED               = of("case.created", true);
    public static final AuditAction CASE_UPDATED               = of("case.updated", true);
    public static final AuditAction CASE_PARTICIPANT_ADDED     = of("case.participant_added", true);
    public static final AuditAction CASE_PARTICIPANT_REMOVED   = of("case.participant_removed", true);
    // Timeline-visible for the same reason: entering a stage, completing the case and
    // a stage being skipped by a branch are exactly the progress narrative the
    // customer's own Activity Timeline exists to show.
    public static final AuditAction CASE_STAGE_ENTERED         = of("case.stage_entered", true);
    public static final AuditAction CASE_COMPLETED             = of("case.completed", true);
    public static final AuditAction MILESTONE_SKIPPED          = of("milestone.skipped", true);
    // Timeline-visible for the same reason: satisfying or waiving a requirement, and
    // the milestone that settles because of it, are the customer's own progress --
    // exactly what Task 15's stage/completion events already are.
    public static final AuditAction REQUIREMENT_SATISFIED      = of("requirement.satisfied", true);
    public static final AuditAction REQUIREMENT_WAIVED         = of("requirement.waived", true);
    public static final AuditAction MILESTONE_COMPLETED        = of("milestone.completed", true);
    // Timeline-visible for the same reason case.stage_entered is: who let the case
    // leave a gated stage is exactly the progress narrative the customer's own
    // Activity Timeline exists to show.
    public static final AuditAction CASE_STAGE_EXIT_APPROVED   = of("case.stage_exit_approved", true);
    public static final AuditAction CASE_STAGE_EXIT_REJECTED   = of("case.stage_exit_rejected", true);
    // milestone.force_completed is deliberately a DIFFERENT key from
    // milestone.completed (Q5). Sub-project 1 shipped UserAdminService.deactivate
    // writing the user.created key, and because audit_event is append-only those
    // rows can never be corrected -- a forced completion recorded as an ordinary
    // one would be the same mistake with a worse consequence.
    public static final AuditAction MILESTONE_FORCE_COMPLETED  = of("milestone.force_completed", true);
    public static final AuditAction MILESTONE_FORCE_REJECTED   = of("milestone.force_rejected", true);
    // Timeline-visible for the same reason case.stage_entered is: pausing, resuming
    // and rewinding a milestone for rework are the customer's own progress
    // narrative, not internal administration.
    public static final AuditAction CASE_HELD                  = of("case.held", true);
    public static final AuditAction CASE_RESUMED               = of("case.resumed", true);
    public static final AuditAction MILESTONE_REOPENED         = of("milestone.reopened", true);
    public static final AuditAction MILESTONE_REASSIGNED       = of("milestone.reassigned", true);
    // NOT timeline-visible: which internal workflow version a case follows is the
    // vendor's own configuration, not the customer's business -- the same reasoning
    // user.created carries.
    public static final AuditAction CASE_MIGRATED              = of("case.migrated", false);

    private static AuditAction of(String key, boolean timelineVisible) {
        AuditAction a = new AuditAction(key, timelineVisible);
        BY_KEY.put(key, a);
        return a;
    }

    private AuditActions() {}

    public static Collection<AuditAction> all() { return List.copyOf(BY_KEY.values()); }

    public static Optional<AuditAction> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
