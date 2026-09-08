package co.ara.onboarding.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Deliberately carries no caseId -- the case is the URL's own nesting id
 * ({@code CommentService#create(UUID, CreateCommentRequest)}), so there is
 * nothing here for a caller to disagree with it about. resourceId is
 * independently confirmed to belong to the resolved case before a comment is
 * ever written against it (see {@code CommentService}'s own doc comment); a
 * mismatched or invented one is refused as not found, never silently
 * recorded. body's non-blank check mirrors the database CHECK
 * ({@code length(btrim(body)) > 0}) in Java, same as
 * {@code task.cancellation_reason} in Task 18 -- the database is the
 * backstop, not the error message.
 */
public record CreateCommentRequest(@NotNull CommentResourceType resourceType,
                                   @NotNull UUID resourceId,
                                   @NotBlank String body) {}
