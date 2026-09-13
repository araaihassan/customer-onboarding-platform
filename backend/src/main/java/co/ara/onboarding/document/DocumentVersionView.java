package co.ara.onboarding.document;

import java.time.Instant;
import java.util.UUID;

/**
 * A flat projection of {@link DocumentVersion} -- the read shape
 * {@link DocumentService#addVersion} returns, and (later) the version-history
 * read.
 */
public record DocumentVersionView(UUID id, UUID documentId, int versionNo, long sizeBytes,
                                  String contentType, String sha256, ReviewStatus reviewStatus,
                                  UUID reviewedBy, Instant reviewedAt, String reviewNote,
                                  UUID uploadedBy, Instant uploadedAt) {}
