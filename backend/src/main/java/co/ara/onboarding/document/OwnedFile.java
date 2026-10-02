package co.ara.onboarding.document;

import java.util.UUID;

/**
 * What {@link AgreementFiles} hands back to its caller after a write: enough to
 * populate {@code agreement}/{@code agreement_version}'s own {@code document_id}/
 * {@code document_version_id} columns without that module ever seeing a
 * {@link Document} or {@link DocumentVersion} type itself.
 */
public record OwnedFile(UUID documentId, UUID documentVersionId, int versionNumber, String sha256) {}
