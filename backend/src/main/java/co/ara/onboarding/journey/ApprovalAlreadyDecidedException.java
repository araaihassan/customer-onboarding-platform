package co.ara.onboarding.journey;

import java.util.UUID;

/** The approval is no longer PENDING; a decision was already recorded and cannot be replaced. */
public class ApprovalAlreadyDecidedException extends RuntimeException {

    public ApprovalAlreadyDecidedException(UUID approvalId) {
        super("Approval " + approvalId + " has already been decided");
    }
}
