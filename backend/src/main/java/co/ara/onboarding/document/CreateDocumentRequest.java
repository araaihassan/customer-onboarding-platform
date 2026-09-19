package co.ara.onboarding.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code caseId} is deliberately NOT a field -- it is
 * {@link DocumentService#upload}'s own URL-nesting parameter, the same "the
 * URL, not the body, names the parent" shape {@code CreateTaskRequest}'s own
 * javadoc explains for a different reason (there it is escalation risk;
 * here there is simply no second place a case id could come from).
 * {@code customerId} is never accepted at all, anywhere: it is always copied
 * from the resolved case (CLAUDE.md's write-path invariant), so there is no
 * field here that could even be left to override it by omission.
 *
 * {@code targetDepartmentId} is resolved through
 * {@code customer.OrgUnitResolver} before being written -- the same existing
 * component {@code CustomerService} already uses for the identical id, so a
 * bogus department id 404s rather than surfacing a raw FK violation.
 * {@code ownerContactId}, when supplied, is likewise resolved before being
 * written -- {@link DocumentService#resolveOwnerContact} -- through
 * {@link co.ara.onboarding.authz.AuthorizedQuery} under {@code contact.view},
 * refusing a contact belonging to a different customer than the case with
 * {@link IllegalArgumentException} (400). This was originally left as a raw,
 * unvalidated FK write (a documented, deliberate simplification deferred to
 * Task 17's PATCH); found during a later security review to be a real
 * cross-customer disclosure vector instead, because {@code owner_contact_id}
 * is exactly what gates CONTACT_ONLY visibility
 * ({@code scoping.DocumentAudienceFilter}) -- fixed directly on this path
 * rather than deferred further. See {@link DocumentService#resolveOwnerContact}'s
 * own javadoc for the full reasoning.
 */
public record CreateDocumentRequest(@NotBlank @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE) String name,
                                    @NotNull DocumentCategory category,
                                    @NotNull VisibilityTier visibilityTier,
                                    UUID targetDepartmentId, String targetContactLabel,
                                    UUID ownerContactId, Instant expiresAt) {

    /**
     * Task 22 review Finding 2 (empirical, not just read from source -- see
     * {@code DocumentControllerTest.createRejectsANameContainingADoubleQuote}'s
     * own javadoc for the literal header values a real upload+download produced
     * before this restriction existed): a name reaching {@link
     * DocumentController#content}'s {@code Content-Disposition} header through
     * Spring 6.2.1's {@code ContentDisposition} with a UTF-8 charset embeds an
     * unescaped double quote from its legacy {@code filename="..."} parameter's
     * own quoted-printable encoder, breaking that quoted string for a parser
     * that reads it literally -- confirmed real, not theoretical. Control
     * characters (CR/LF/NUL among them) are rejected too, as uncontroversial
     * defense-in-depth no legitimate document name needs, even though the same
     * probe showed they are, unlike the quote, already safely hex/percent-encoded
     * by both of that header's parameters and pose no equivalent risk today.
     * {@link PatchDocumentRequest#name} carries the identical restriction, via
     * this same constant, since it accepts a rename through the same
     * {@link DocumentService#patch} path that flows into the same header later.
     */
    static final String NAME_PATTERN = "^[^\\x00-\\x1F\\x7F\"]*$";
    static final String NAME_MESSAGE = "must not contain a control character (CR/LF/NUL included) or a double quote";
}
