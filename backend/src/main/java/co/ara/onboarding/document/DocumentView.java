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
 *
 * {@code currentVersionNumber} (Ruling 3, sub-project 4 Task 33) exists
 * because the content-download endpoint (already built,
 * {@code GET /documents/{id}/versions/{versionNo}/content}) takes a version
 * NUMBER, not the bare {@code currentVersionId} UUID above -- a caller with
 * only this view had no correct way to build that URL. It is a nullable
 * projection of {@code DocumentVersion.versionNo} resolved from {@code
 * currentVersionId} in {@code DocumentService.toView}, never null in
 * practice once a document has any version, since {@code currentVersionId}
 * is kept in step with the latest version on every append -- nullable only
 * because {@code currentVersionId} itself is.
 */
public record DocumentView(UUID id, UUID caseId, UUID customerId, String name,
                           DocumentCategory category, VisibilityTier visibilityTier,
                           UUID targetDepartmentId, String targetContactLabel, UUID ownerContactId,
                           Instant expiresAt, DocumentStatus status, UUID currentVersionId,
                           Integer currentVersionNumber,
                           UUID uploadedBy, Instant createdAt, Instant updatedAt) {}
