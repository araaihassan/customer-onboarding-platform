package co.ara.onboarding.document;

import java.util.UUID;

/**
 * Published, in the writer's transaction, when a customer uploads a document through the portal
 * ({@code requestId} null) and again when staff fulfil a request with a document ({@code
 * requestId} set). The listener collapses the two into one notification per recipient. 6B spec 5.2.
 */
public record DocumentUploaded(UUID documentId, UUID requestId, UUID caseId, UUID actorId) {}
