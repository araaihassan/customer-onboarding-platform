package co.ara.onboarding.auth;

/** Plain text only in sub-project 1; templating and HTML arrive with the notification system. */
public record EmailMessage(String to, String subject, String body) {}
