package co.ara.onboarding.workflow;

/**
 * A branch rule's target stage, a stage's fallback, or a milestone's dependency
 * named a client-local key that no element in the same request declared. This is a
 * client typo, not a programming error, so it names the offending key rather than
 * surfacing as a null foreign key or a database constraint violation.
 */
public class UnknownReferenceException extends RuntimeException {

    public UnknownReferenceException(String key) {
        super("Unknown reference key: " + key);
    }
}
