package co.ara.onboarding.notification;

/** One row of the preferences pane (6B spec 8); {@code locked} marks the type policy keeps on. */
public record TypePreferenceView(NotificationType type, String label, boolean inApp, boolean email, boolean locked) {}
