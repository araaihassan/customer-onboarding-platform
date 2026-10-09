package co.ara.onboarding.task;

import java.util.UUID;

/**
 * Published by {@link CommentService#create} after {@code comment.added} is recorded, inside the
 * same transaction. {@code notification} listens for it (6B, NEW_COMMENT); {@code task} never names
 * a notification type.
 */
public record CommentAdded(UUID commentId, CommentResourceType resourceType, UUID resourceId, UUID caseId, UUID authorId) {}
