package co.ara.onboarding.document;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Task 22's own minimal request body for {@code POST /documents/{id}/links} --
 * {@link DocumentSharingService#link} takes a plain {@code (UUID, UUID)} pair
 * rather than an existing record, so this is the request-side counterpart
 * the controller needs. The cross-reference check (does {@code caseId}
 * resolve to a case belonging to the same customer as the document) is
 * {@link DocumentSharingService#link}'s own job, not bean validation's.
 */
public record LinkDocumentRequest(@NotNull UUID caseId) {}
