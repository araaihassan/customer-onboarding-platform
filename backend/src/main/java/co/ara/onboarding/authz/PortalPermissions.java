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
 */
public final class PortalPermissions {

    private PortalPermissions() {}

    public static Map<String, Scope> forContact() {
        return Map.of(
                PermissionKeys.DOCUMENT_VIEW,   Scope.ALL,
                PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL,
                PermissionKeys.CASE_VIEW,       Scope.ALL);
    }

    /**
     * Q20's programme read and Q22's plan approval. Additive over forContact().
     *
     * PLAN_APPROVE_SCHEDULE, not a "PLAN_DECIDE" key -- PermissionKeys has no
     * such constant. Sub-project 3A catalogued two schedule-gate permissions,
     * PLAN_ISSUE (an internal actor issuing a revision) and
     * PLAN_APPROVE_SCHEDULE (recording the customer's decision on it); the
     * sponsor's own approve button is the second one.
     */
    public static Map<String, Scope> forSponsor() {
        return Map.of(
                PermissionKeys.DOCUMENT_VIEW,   Scope.ALL,
                PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL,
                PermissionKeys.CASE_VIEW,       Scope.ALL,
                PermissionKeys.PROGRAMME_VIEW,  Scope.ALL,
                PermissionKeys.PLAN_APPROVE_SCHEDULE, Scope.ALL);
    }
}
