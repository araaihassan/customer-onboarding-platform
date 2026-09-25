package co.ara.onboarding.document;

import java.time.Instant;
import java.util.UUID;

/**
 * A flat projection of {@link DocumentCaseLink} -- the read shape
 * {@link DocumentSharingService#link} and {@link DocumentSharingService#unlink}
 * both return. {@code caseId} here is the LINKED (target) case, never the
 * document's own home case ({@code Document#getCaseId()}). {@code revokedAt} is
 * {@code null} on a live link, exactly as the entity itself models it (design
 * spec 4.4: "unlinking is a column, not a DELETE"), the identical shape
 * {@link DocumentShareView} already uses for shares.
 */
public record DocumentCaseLinkView(UUID id, UUID documentId, UUID caseId,
                                   UUID linkedBy, Instant linkedAt, Instant revokedAt) {}
