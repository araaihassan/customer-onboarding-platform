package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * The decider is the same person who requested this approval. Without this check
 * a user holding both the request and decide permissions -- Administrator does --
 * could request and approve a forced completion in two calls with no second person
 * involved, and the approval flow would decorate a single click.
 */
public class SelfApprovalException extends RuntimeException {

    public SelfApprovalException(UUID approvalId) {
        super("Approval " + approvalId + " cannot be decided by the user who requested it");
    }
}
