package co.ara.onboarding.agreement;

import java.util.UUID;

/**
 * {@code displayName} is resolved best-effort through {@code AuthorizedQuery}
 * under {@code contact.view}/{@code user.view} depending on {@code kind}, and
 * is {@code null} when the viewer holds neither -- a name lookup never fails
 * the read (CLAUDE.md's finding about a nested {@code workflow.view} lookup
 * 404-ing a whole case read is exactly what this guards against). {@code
 * signed} is derived from whether any {@code AgreementSignature} row exists
 * for this signatory, not stored on the signatory itself.
 */
public record AgreementSignatoryView(UUID id, SignatoryKind kind, UUID contactId, UUID userId,
        String displayName, String displayRole, int sortOrder, boolean signed) {}
