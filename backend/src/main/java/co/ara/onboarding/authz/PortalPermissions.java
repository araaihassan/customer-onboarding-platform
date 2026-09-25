package co.ara.onboarding.authz;

import java.util.Map;

/**
 * What a customer contact may do, as a code constant -- never a user_role row.
 *
 * Portal authority is NOT role-shaped. The PRD's external-user list is uniform
 * (view progress, upload requested documents, download agreements), and the
 * twelve seeded role templates are all internal. Letting contacts into role
 * assignment would reopen the escalation shape sub-project 1 fought three times,
 * and a single mis-seeded template would become a cross-company leak.
 *
 * Because this is a constant and not a grant, no administrator can create it and
 * no hand-edited database row can widen it. PortalAuthorityTest pins the exact
 * set so that widening it is a deliberate edit with a failing test attached.
 *
 * EVERY KEY HERE IS HELD AT ALL SCOPE, and is safe ONLY because
 * scoping.DocumentAudienceFilter (sub-project 4, Task 13) narrows it. Adding a
 * key whose entity type declares no AudienceFilter grants that entity
 * tenant-wide to every external user. Do not add one without adding its filter
 * in the same commit.
 *
 * REMOVED after this task's own security review (post-Task-3 fix round):
 * case.view, programme.view and plan.approve_schedule. The original version of
 * this class granted all three at ALL, on the (wrong) assumption that
 * DocumentAudienceFilter's narrowing extended to them -- it does not, and never
 * will: that filter only ever covers Document. Case, Programme and PlanRevision
 * have no AudienceFilter planned anywhere in this plan, so at ALL scope
 * AuthorizationPredicateBuilder matched every row tenant-wide. Concretely,
 * PlanRevisionController.decide resolves its target only by revisionId under
 * PLAN_APPROVE_SCHEDULE and never checks the {caseId} path variable against it,
 * so a portal contact of customer A marked primaryContact=true could have
 * decided customer B's schedule revision -- an immutable write releasing B's
 * held journey. Portal case/programme/plan-approval access is real product
 * scope (Q20, Q22's customer-facing half) but belongs to sub-project 7
 * (Customer Portal), which is where it gets built with its own narrowing
 * mechanism -- not smuggled in here at ALL scope with none.
 */
public final class PortalPermissions {

    private PortalPermissions() {}

    public static Map<String, Scope> forContact() {
        return Map.of(
                PermissionKeys.DOCUMENT_VIEW,   Scope.ALL,
                PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL);
    }

    /**
     * Identical to forContact() today -- deliberately, not an oversight. This
     * method stays separate from forContact() as the seam sub-project 7 is
     * expected to widen: a sponsor's real additive authority (Q20's programme
     * read, Q22's plan-approval decision) needs its own narrowing mechanism the
     * way DocumentAudienceFilter narrows document.view/upload, and until that
     * mechanism exists here there is nothing safe to add. See this class's own
     * javadoc for what was removed and why.
     */
    public static Map<String, Scope> forSponsor() {
        return Map.of(
                PermissionKeys.DOCUMENT_VIEW,   Scope.ALL,
                PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL);
    }
}
