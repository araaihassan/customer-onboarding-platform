package co.ara.onboarding.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * Task 26: the portal-facing counterpart of {@link CreateDocumentRequest},
 * deliberately narrower. {@code caseId} is the URL's own path parameter, the
 * same "the URL, not the body, names the parent" shape {@link
 * CreateDocumentRequest}'s own javadoc already explains.
 *
 * Deliberately has NO {@code targetDepartmentId}, {@code targetContactLabel}
 * or {@code ownerContactId} field -- not merely fields a caller could send and
 * have ignored, but fields that DO NOT EXIST on this type at all. A portal
 * caller has no basis to set internal routing (those two fields are
 * INTERNAL-audience targeting, {@code scoping.DocumentAudienceFilter
 * #internalAudience}'s own concern, never consulted for a portal reader in
 * the first place), and {@code ownerContactId} must never be settable by the
 * caller for this path -- {@link DocumentService#uploadFromPortal} forces it
 * unconditionally to the ACTING contact resolved server-side, so giving this
 * type a field for it would invite a caller to believe setting it does
 * something.
 *
 * <p><b>A real, deliberate product gap, noted here rather than built around
 * silently:</b> {@code visibilityTier} of {@link VisibilityTier#SENSITIVE}
 * ("Selected contacts only" in SCREENS.md §17) implies the uploading customer
 * picks specific people to share the document with -- but this sub-project
 * builds no portal-side sharing mechanism at all. {@code document.share} is an
 * internal-only permission ({@code PortalPermissions} never grants it), so a
 * portal-uploaded SENSITIVE document reaches nobody by tier alone
 * ({@code scoping.DocumentAudienceFilter#portalAudience}'s own javadoc: "SENSITIVE
 * appears in neither disjunct... only ever through the explicit share") until
 * an internal staff member shares it via the already-built internal mechanism
 * (Task 19). Choosing SENSITIVE from the portal today is therefore correct but
 * not yet useful on its own -- a future sub-project (7, Customer Portal) is
 * where portal-side sharing would actually get built, not here.
 */
public record PortalCreateDocumentRequest(
        @NotBlank @Pattern(regexp = CreateDocumentRequest.NAME_PATTERN, message = CreateDocumentRequest.NAME_MESSAGE)
        String name,
        @NotNull DocumentCategory category,
        @NotNull VisibilityTier visibilityTier,
        Instant expiresAt) {
}
