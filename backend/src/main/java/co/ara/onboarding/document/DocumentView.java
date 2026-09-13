package co.ara.onboarding.document;

import java.time.Instant;
import java.util.UUID;

/**
 * A flat projection of every field on {@link Document} -- the same "carries
 * every field a request could ever need to round-trip" shape TaskView and
 * ProgrammeView already use. There is no Update*Request yet (write paths land
 * in a later task), so the CLAUDE.md full-replace/field-alignment invariant
 * does not bind here yet -- this is simply the read shape callers of
 * {@code list}/{@code forCase}/{@code get} need.
 */
public record DocumentView(UUID id, UUID caseId, UUID customerId, String name,
                           DocumentCategory category, VisibilityTier visibilityTier,
                           UUID targetDepartmentId, String targetContactLabel, UUID ownerContactId,
                           Instant expiresAt, DocumentStatus status, UUID currentVersionId,
                           UUID uploadedBy, Instant createdAt, Instant updatedAt) {}
