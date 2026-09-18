package co.ara.onboarding.document;

import java.time.Instant;
import java.util.UUID;

/**
 * A flat projection of {@link DocumentShare} -- the read shape
 * {@link DocumentSharingService#share} and {@link DocumentSharingService#revokeShare}
 * both return. {@code revokedAt} is {@code null} on a live share, exactly as the
 * entity itself models it (design spec 4.3: "revocation is a column, not a DELETE").
 */
public record DocumentShareView(UUID id, UUID documentId, SharePrincipalType principalType,
                                UUID principalId, UUID grantedBy, Instant grantedAt, Instant revokedAt) {}
