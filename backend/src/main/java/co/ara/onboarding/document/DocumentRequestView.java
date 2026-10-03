package co.ara.onboarding.document;

import java.time.Instant;
import java.util.UUID;

/**
 * A flat projection of every field on {@link DocumentRequest} -- the same
 * "carries every field a request could ever need to round-trip" shape {@link
 * DocumentShareView}/{@link DocumentCaseLinkView}/{@link DocumentView} already
 * use in this package. There is no {@code Update*Request} for this entity
 * (its lifecycle moves entirely through {@code create}/{@code withdraw}/the
 * not-yet-built {@code fulfil}, never a full-replace PUT), so the CLAUDE.md
 * field-alignment invariant does not bind here.
 */
public record DocumentRequestView(UUID id, UUID caseId, UUID requirementId, UUID requestedOfContactId,
                                  DocumentCategory category, String description, Instant dueAt,
                                  boolean requiresReview, DocumentRequestStatus status,
                                  UUID fulfilledDocumentId, UUID requestedBy, Instant requestedAt,
                                  int remindersSent, Instant lastRemindedAt) {

    /** The pre-reminder shape, kept so positional callers need no change (spec 1.2.11). */
    public DocumentRequestView(UUID id, UUID caseId, UUID requirementId, UUID requestedOfContactId,
                               DocumentCategory category, String description, Instant dueAt,
                               boolean requiresReview, DocumentRequestStatus status,
                               UUID fulfilledDocumentId, UUID requestedBy, Instant requestedAt) {
        this(id, caseId, requirementId, requestedOfContactId, category, description, dueAt, requiresReview,
                status, fulfilledDocumentId, requestedBy, requestedAt, 0, null);
    }
}
