package co.ara.onboarding.task;

import java.time.Instant;
import java.util.UUID;

/**
 * editedAt is null until the first successful {@code CommentService#update}
 * -- an edited comment says so (design spec 4.3), and this is the field the
 * brief's own {@code editingMarksTheCommentEdited} test reads directly.
 */
public record CommentView(UUID id, UUID caseId, CommentResourceType resourceType, UUID resourceId,
                          UUID authorId, String body, Instant createdAt, Instant editedAt) {}
