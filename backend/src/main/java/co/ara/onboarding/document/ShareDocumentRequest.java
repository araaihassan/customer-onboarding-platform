package co.ara.onboarding.document;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Task 22's own minimal request body for {@code POST /documents/{id}/shares} --
 * {@link DocumentSharingService#share} takes a plain
 * {@code (UUID, SharePrincipalType, UUID)} triple rather than an existing
 * record, so this is the request-side counterpart the controller needs.
 * {@code principalId} is validated only for presence here: the actual
 * cross-reference check (does this id resolve to a real, in-scope principal
 * of the declared type, and does it belong to the document's own customer)
 * is {@link DocumentSharingService#resolvePrincipal}'s job, not bean
 * validation's.
 */
public record ShareDocumentRequest(@NotNull SharePrincipalType principalType, @NotNull UUID principalId) {}
