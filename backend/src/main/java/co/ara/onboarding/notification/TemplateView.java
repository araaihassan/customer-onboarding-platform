package co.ara.onboarding.notification;

import java.util.UUID;

public record TemplateView(UUID id, String key, String name, String enteredSubject, String enteredBody,
                           String exitedSubject, String exitedBody, boolean active) {}
