package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * QA Q22/Q23 gate 2: {@code resume()} was called directly on a case pinned to
 * a customer-owned template ({@code workflow_template.customer_id} non-null)
 * before that case has ever had an {@code APPROVED} schedule revision. The
 * ordinary {@code case.hold}/{@code case.resume} mechanism from sub-project 2
 * still works once that first approval exists -- see
 * {@link CaseService#resume}'s own comment -- this only refuses the manual
 * shortcut that would otherwise let a holder of plain {@code case.hold}
 * release a hold that {@link CaseService#create} put there for a different
 * reason than a manual pause.
 */
public class PlanApprovalOutstandingException extends RuntimeException {

    public PlanApprovalOutstandingException(UUID caseId) {
        super("Case " + caseId + " cannot resume until its plan's first schedule revision is approved");
    }
}
