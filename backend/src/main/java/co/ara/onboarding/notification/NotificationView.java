package co.ara.onboarding.notification;

import java.time.Instant;
import java.util.UUID;

/** One inbox row as the caller sees it (6B spec 7.3). */
public record NotificationView(UUID id, NotificationType type, String title, String body, String linkPath,
                               Tone tone, boolean read, Instant createdAt) {}
