package co.ara.onboarding.notification;

/** A well-formed request that breaks a rule the 6B spec answers with 422. */
public class NotificationRuleException extends RuntimeException {
    public NotificationRuleException(String message) { super(message); }
}
