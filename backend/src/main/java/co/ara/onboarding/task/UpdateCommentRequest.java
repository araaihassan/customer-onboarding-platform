package co.ara.onboarding.task;

import jakarta.validation.constraints.NotBlank;

/**
 * An edit changes only the words -- who wrote it, what it is attached to and
 * which case it lives under are facts about the comment's identity, not
 * something an edit reassigns. Unlike {@code UpdateTaskRequest}, this is not
 * a stand-in for a literal full-replace PUT of every column: CLAUDE.md's
 * full-replace invariant applies to a request type that stands in for
 * "everything the record can express," and resourceType/resourceId/authorId
 * are exactly the fields Task 23's own design (spec 4.3, 6.1) treats as
 * fixed at creation, never editable at all -- there is no field of
 * {@link CommentView} that this record silently omits and therefore risks
 * blanking.
 */
public record UpdateCommentRequest(@NotBlank String body) {}
