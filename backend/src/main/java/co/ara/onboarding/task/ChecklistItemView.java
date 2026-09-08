package co.ara.onboarding.task;

import java.util.UUID;

public record ChecklistItemView(UUID id, UUID taskId, String label, boolean done, int ordinal) {}
