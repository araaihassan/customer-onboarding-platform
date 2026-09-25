package co.ara.onboarding.document;

import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /documents/{id}/versions/{n}/review} (design spec
 * §8). {@code note} is deliberately NOT {@code @NotBlank} -- unlike a retire
 * or withdraw reason (both of which leave no other record the action ever
 * happened), a review decision is already fully recorded by
 * {@code reviewStatus}/{@code reviewedBy}/{@code reviewedAt} themselves,
 * {@link DocumentVersion#getReviewNote()} carries no {@code NOT NULL}
 * constraint at the database, and a quick "approved, no comment" click is a
 * legitimate real-world reviewer action this field should not block.
 */
public record ReviewVersionRequest(@NotNull ReviewDecision decision, String note) {}
