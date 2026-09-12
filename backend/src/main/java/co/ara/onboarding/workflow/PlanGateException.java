package co.ara.onboarding.workflow;

/**
 * {@link PlanShapeService} refuses an operation because the plan's OWN current
 * state makes it semantically impossible right now -- never because the
 * request was malformed or the caller lacked authority. Three distinct
 * shapes, all this one type (sub-project 3A, Task 20 / QA Q22 gate 1):
 *
 * <ul>
 *   <li>{@code submit} on a version that is still DRAFT -- a shape that can
 *       still change cannot be approved;</li>
 *   <li>{@code submit} on a version whose owning template is a catalogue
 *       template ({@code customerId} null) -- the two-gate approval story is
 *       defined at the customer tier only;</li>
 *   <li>{@code decide} when there is no currently-SUBMITTED row -- a decision
 *       is one-shot, so deciding an already-decided (or never-submitted)
 *       version is refused rather than silently overwriting the prior
 *       decision.</li>
 * </ul>
 *
 * 422, the same "well-formed but rejected" shape {@code PublishValidationException}
 * and {@code NotCloneableException} already carry in this package -- see
 * {@code WorkflowExceptionHandler}.
 */
public class PlanGateException extends RuntimeException {

    public PlanGateException(String message) {
        super(message);
    }
}
