package co.ara.onboarding.document;

import java.util.UUID;

/**
 * Published, in the writer's transaction, when a document request opens: an ad-hoc
 * {@code DocumentRequestService.create} (after its {@code document.requested} record) or a
 * requirement-instantiated one at case creation. 6B spec 5.2.
 */
public record DocumentRequested(UUID requestId, UUID caseId, UUID actorId) {}
