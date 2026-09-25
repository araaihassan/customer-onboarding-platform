package co.ara.onboarding.document;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.PortalContactDirectory;
import co.ara.onboarding.authz.PortalContactFacts;
import co.ara.onboarding.journey.Case;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CREATED;

/**
 * Task 26: {@code GET /portal/documents} and {@code POST
 * /portal/cases/{caseId}/documents} -- the two portal-facing rows spec §8
 * lists, and the FIRST portal (external customer) WRITE path this codebase
 * has ever built.
 *
 * <p>{@code list} needs no special handling at all: it delegates straight to
 * {@link DocumentService#list}, which already takes no {@code caseId}, and
 * {@code scoping.DocumentAudienceFilter}'s existing {@code portalAudience}
 * branch already does 100% of the correct narrowing for a portal caller (Task
 * 13). There is nothing for this controller to add on top.
 *
 * <p>{@code upload} is the genuinely new work, and follows a strict sequence
 * -- each step refuses fast, before any I/O against the upload's bytes has
 * happened:
 * <ol>
 *   <li>Resolve the acting contact via {@link PortalContactDirectory
 *       #findActiveContactForUser}, keyed on the CURRENT authenticated
 *       principal's own user id (never a caller-supplied one). This call
 *       ALREADY returns empty for a retired contact, or one whose linked
 *       {@code app_user} is no longer ACTIVE -- refused here with the same
 *       404 an absent case gets, no separate check needed.</li>
 *   <li>Resolve and validate {@code caseId} via {@link PortalCaseAccess
 *       #resolveForContact}, which closes the case-existence oracle a naive
 *       {@code AuthorizedQuery} resolution would have reopened for this
 *       audience -- see that class's own javadoc for the full reasoning.</li>
 *   <li>Delegate to {@link DocumentService#uploadFromPortal}, which reuses
 *       every bit of Task 7's upload-hardening ruling (the size ceiling, the
 *       sniffed-content MIME check, the SHA-256 digest) and forces the
 *       document's owner to the ACTING contact, never a value the request
 *       body could supply.</li>
 * </ol>
 *
 * <p>Thin by the same convention {@link DocumentController}/{@link
 * DocumentRequestController} already establish: no authorization DECISION is
 * made in this class itself (every step above is a call into an already-
 * gated or already-narrowed collaborator) -- it only threads the resolved
 * values from one call into the next. {@code
 * ModuleBoundaryTest.controllersDoNotUseRepositoriesDirectly} stops this
 * class reaching a repository directly, and it does not: {@link
 * PortalCaseAccess} is the one place that does, and it is a named exclusion
 * in {@code AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS} for exactly
 * that reason.
 *
 * <p>Both endpoints return {@link PortalDocumentView}, not the internal
 * {@link DocumentView} -- fixed in a review round. See that type's own
 * javadoc for why {@code targetDepartmentId}/{@code targetContactLabel}/
 * {@code uploadedBy} must never cross this boundary.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}/portal")
public class PortalDocumentController {

    private static final String NOT_FOUND =
            "No active linked contact for the caller, or the case is absent, cross-tenant, or belongs to a "
                    + "different customer than the acting contact (spec 6.8: identical response either way)";

    private final DocumentService documents;
    private final PortalCaseAccess caseAccess;
    private final PortalContactDirectory contacts;
    private final AuthContextProvider contextProvider;

    public PortalDocumentController(DocumentService documents, PortalCaseAccess caseAccess,
                                    PortalContactDirectory contacts, AuthContextProvider contextProvider) {
        this.documents = documents;
        this.caseAccess = caseAccess;
        this.contacts = contacts;
        this.contextProvider = contextProvider;
    }

    @GetMapping("/documents")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Every document visible to the acting contact, per scoping.DocumentAudienceFilter's portal branch")
    })
    public Page<PortalDocumentView> list(Pageable pageable) {
        // No tier filter on the portal listing -- spec §8 never routes a
        // portal caller through the tier query parameter Task 32 added for
        // the operator-facing `docs` screen.
        return documents.list(null, pageable).map(PortalDocumentView::from);
    }

    @PostMapping(value = "/cases/{caseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Uploaded, pinned to version 1, owned by the acting contact"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "413", description = "The declared size exceeds app.storage.max-upload-bytes",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "The sniffed content type is not accepted for the declared category",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PortalDocumentView upload(@PathVariable UUID caseId, @RequestPart("file") MultipartFile file,
                               @Valid @RequestPart("metadata") PortalCreateDocumentRequest metadata)
            throws IOException {
        // principal(), not current(): this controller runs with no transaction of
        // its own (controllers never are, in this codebase), and current() makes
        // an extra AuthorizedActor lookup this call does not need -- the raw
        // authenticated userId the JWT already carries is all
        // PortalContactDirectory needs. Avoids a bare repository read racing
        // ahead of any transaction that would bind the tenant GUC.
        UUID actingUserId = contextProvider.principal().userId();
        PortalContactFacts contact = contacts.findActiveContactForUser(actingUserId)
                .orElseThrow(() -> new NoSuchElementException("Not found"));
        Case c = caseAccess.resolveForContact(caseId, contact.customerId());

        CreateDocumentRequest internal = new CreateDocumentRequest(
                metadata.name(), metadata.category(), metadata.visibilityTier(),
                null, null, null, metadata.expiresAt());

        return PortalDocumentView.from(documents.uploadFromPortal(c, contact.id(), internal,
                file.getInputStream(), file.getSize(), file.getContentType()));
    }
}
