package co.ara.onboarding.task;

import jakarta.validation.constraints.NotBlank;

/** Adds one checklist line to a task. Ordinal is assigned by the service, never supplied here. */
public record AddChecklistItemRequest(@NotBlank String label) {}
