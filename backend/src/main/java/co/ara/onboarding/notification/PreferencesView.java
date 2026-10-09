package co.ara.onboarding.notification;

import java.util.List;

/** The caller's preferences (6B spec 8): every type in enum order, and the email cadence. */
public record PreferencesView(EmailCadence emailCadence, List<TypePreferenceView> types) {}
