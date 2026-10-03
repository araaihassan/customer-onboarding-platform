package co.ara.onboarding.sla;

/** A well-formed request that breaks a rule the spec answers with 422 (spec §8). */
public class UnprocessableException extends RuntimeException {
    public UnprocessableException(String message) { super(message); }
}
