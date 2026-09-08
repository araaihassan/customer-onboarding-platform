package co.ara.onboarding.task;

import jakarta.validation.constraints.NotBlank;

/** Structural edit -- gated task.manage, not task.complete. Renaming is not doing the work. */
public record RenameChecklistItemRequest(@NotBlank String label) {}
