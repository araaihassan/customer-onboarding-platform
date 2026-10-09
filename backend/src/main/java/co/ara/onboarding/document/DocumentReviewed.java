package co.ara.onboarding.document;

import java.util.UUID;

/**
 * Published, in the reviewer's transaction, after {@code document.reviewed} is recorded and
 * before the decision's own satisfy/reopen consequences. {@code decision} is
 * {@code "APPROVED"} or {@code "REJECTED"}. 6B spec 5.2.
 */
public record DocumentReviewed(UUID documentId, int versionNo, UUID caseId, String decision, UUID actorId) {}
