package co.ara.onboarding.workflow;

import java.util.UUID;

/**
 * QA Q21: one clone per customer per catalogue template. Checked at the service
 * layer first, before any write, in {@link CustomerTemplateService#clone} --
 * V18's partial unique index ({@code workflow_template_customer_clone_uq}) is
 * the last line of defense against a race between two concurrent clone calls,
 * not the first check a caller should ever meet, the same "name the business
 * rule explicitly instead of leaving it to the database's generic violation"
 * reasoning {@link DraftAlreadyExistsException}'s own doc comment gives.
 *
 * 409, not 422: the request was well-formed and the source was perfectly
 * cloneable -- it is the record's current state (an existing clone already
 * occupies this (source, customer) pair) that makes a second one a conflict.
 */
public class DuplicateCloneException extends RuntimeException {

    public DuplicateCloneException(UUID sourceTemplateId, UUID customerId) {
        super("Customer " + customerId + " already holds a clone of template " + sourceTemplateId);
    }
}
