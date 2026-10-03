package co.ara.onboarding.identity;

/** A reporting-line change that cannot be applied as asked (e.g. a user as their own manager); 422. */
public class ReportingLineException extends RuntimeException {
    public ReportingLineException(String message) { super(message); }
}
