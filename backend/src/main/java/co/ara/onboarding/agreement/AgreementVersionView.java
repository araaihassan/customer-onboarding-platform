package co.ara.onboarding.agreement;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code reviewDecision}/{@code reviewerId}/{@code reviewedAt}/{@code reviewReason}
 * are all {@code null} for a version nobody has reviewed yet -- {@link
 * AgreementVersionReview} is its own append-only row, one per version at most,
 * not a column on {@link AgreementVersion} itself.
 */
public record AgreementVersionView(UUID id, int versionNumber, UUID submittedBy, Instant submittedAt,
        UUID lastEditedBy, String contentSha256, UUID documentVersionId, String documentSha256,
        ReviewDecision reviewDecision, UUID reviewerId, Instant reviewedAt, String reviewReason) {}
