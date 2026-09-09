package co.ara.onboarding.workflow;

import java.util.UUID;

/**
 * CustomerTemplateService.clone refuses a source template that is structurally
 * ineligible to be cloned, for either of two reasons (sub-project 3A, Task 16 /
 * QA Q21):
 *
 * <ul>
 *   <li>it has never been published ({@code currentVersionId} is null) -- there
 *       is no frozen shape to copy, and a draft's own graph is allowed to be
 *       temporarily incoherent, exactly the reason {@link WorkflowService}
 *       never lets a case pin to a draft either;</li>
 *   <li>it is already itself a customer clone ({@code customerId} is non-null)
 *       -- lineage stays one level deep by design, so a customer's own tailored
 *       template can never be cloned again for a second customer.</li>
 * </ul>
 *
 * 422, not 404 or 409: the source template was found and understood, but its
 * own current state makes the operation semantically impossible -- the same
 * "well-formed but rejected" shape {@code PublishValidationException} already
 * carries in this package.
 */
public class NotCloneableException extends RuntimeException {

    public NotCloneableException(UUID templateId, String reason) {
        super("Template " + templateId + " cannot be cloned: " + reason);
    }
}
