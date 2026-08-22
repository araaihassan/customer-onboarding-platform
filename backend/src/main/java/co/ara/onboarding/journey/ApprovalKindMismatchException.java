package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * The caller reached a STAGE_EXIT approval through the FORCE_COMPLETE decide path,
 * or the reverse. The two are structurally distinct (the database CHECK
 * {@code approval_target_matches_kind} ties kind to target), and their decide
 * methods are gated by different permissions -- approval.decide is ORG-scoped,
 * milestone.force_approve is ALL-only -- so they must never be interchangeable.
 */
public class ApprovalKindMismatchException extends RuntimeException {

    public ApprovalKindMismatchException(UUID approvalId) {
        super("Approval " + approvalId + " cannot be decided through this path");
    }
}
