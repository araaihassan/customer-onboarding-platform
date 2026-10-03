package co.ara.onboarding.document;

/** A reminder the spec answers with 422: no contact on the request, or the request is not OPEN. */
public class ReminderNotPossibleException extends RuntimeException {
    public ReminderNotPossibleException(String message) { super(message); }
}
