package co.ara.onboarding.document;

import java.time.Instant;
import java.util.UUID;

/**
 * The portal-facing counterpart of {@link DocumentView} -- fixed in a Task 26
 * review round, not part of that task's original shape. {@code GET
 * /portal/documents} and the portal upload's 201 response both used to return
 * the plain internal {@link DocumentView}, which carries {@code
 * targetDepartmentId} (an internal department UUID), {@code
 * targetContactLabel} (an internal routing label) and {@code uploadedBy} (an
 * internal staff user UUID) -- none of which an external customer should see,
 * even as an opaque id. This is the first time internal routing metadata
 * would have crossed the external boundary, and it was cheapest to fix before
 * any consumer existed: no portal UI is in scope for this sub-project, and no
 * frontend hook anywhere in the plan reads either endpoint's response shape.
 *
 * <p>Deliberately narrow, the same philosophy {@link
 * co.ara.onboarding.authz.PortalContactFacts}'s own javadoc states for a
 * different portal-facing type: "a wider fact shape would tempt a future
 * caller to reach past [the sanctioned mechanism] for something this [type]
 * was never meant to answer." Applied here: a portal reader gets exactly what
 * it needs to identify and display a document it can already see through
 * {@code scoping.DocumentAudienceFilter}'s own narrowing -- {@code id} (to
 * reference it later), {@code caseId} (which of the contact's journeys it
 * belongs to -- necessary, not internal-only, since {@code GET
 * /portal/documents} lists across every case the contact can see), {@code
 * name}, {@code category}, {@code visibilityTier}, {@code status}, {@code
 * ownerContactId} (the acting contact's own attribution being echoed back to
 * them is not a disclosure) and {@code expiresAt}. Everything else on {@link
 * DocumentView} -- {@code customerId}, {@code currentVersionId}, {@code
 * createdAt}/{@code updatedAt}, and the three excluded fields above -- stays
 * off this type until an actual portal consumer needs it, the same "drop the
 * field until something needs it" discipline CLAUDE.md already names for
 * {@code MilestoneRoadmapView.taskSummary}, rather than being carried forward
 * here speculatively.
 */
public record PortalDocumentView(UUID id, UUID caseId, String name, DocumentCategory category,
                                 VisibilityTier visibilityTier, DocumentStatus status,
                                 UUID ownerContactId, Instant expiresAt) {

    /** The one mapping from the internal projection to this narrower one. */
    public static PortalDocumentView from(DocumentView v) {
        return new PortalDocumentView(v.id(), v.caseId(), v.name(), v.category(),
                v.visibilityTier(), v.status(), v.ownerContactId(), v.expiresAt());
    }
}
