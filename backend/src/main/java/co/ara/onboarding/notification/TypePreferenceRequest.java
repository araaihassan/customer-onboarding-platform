package co.ara.onboarding.notification;

import jakarta.validation.constraints.NotNull;

/** Boxed booleans so an omitted channel is a 400, never a silent false. */
public record TypePreferenceRequest(@NotNull NotificationType type, @NotNull Boolean inApp, @NotNull Boolean email) {}
