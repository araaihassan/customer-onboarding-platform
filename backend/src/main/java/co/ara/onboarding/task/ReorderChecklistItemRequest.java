package co.ara.onboarding.task;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * Moves one item to a new position among its task's other checklist items.
 * Deliberately per-item rather than a whole reordered list -- the API surface
 * (design spec 7) exposes only {@code PUT /checklist/{itemId}}, no bulk
 * endpoint, so a single item's new ordinal is the shape the transport already
 * commits to. Gated task.manage: reordering is structural, the same as rename.
 */
public record ReorderChecklistItemRequest(@PositiveOrZero int ordinal) {}
