package co.ara.onboarding.document;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The body of {@code POST /document-requests/{id}/fulfil} -- mirrors {@link
 * WithdrawDocumentRequestRequest}'s own shape exactly: one required field,
 * naming the document offered as fulfilment. {@link
 * DocumentRequestService#fulfil} resolves {@code documentId} through {@link
 * co.ara.onboarding.authz.AuthorizedQuery} under {@code document.view} --
 * composed with this method's own {@code document.request} write gate --
 * before anything is written, the identical write-path invariant every other
 * id in this module already follows.
 */
public record FulfilDocumentRequestRequest(@NotNull UUID documentId) {}
