package co.ara.onboarding.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
 * {@code ownerContactId} is not independently resolved this task: {@code
 * document_owner_ck} is what actually requires it be non-null when
 * {@code visibilityTier} is {@code CONTACT_ONLY}, and a dangling id today
 * surfaces as a raw FK violation -- the same documented, deliberate
 * simplification other not-yet-decided cross-reference paths in this
 * codebase carry until a real need forces the question (Task 17's PATCH,
 * which owns retargeting, is the more natural place to close it).
 */
public record CreateDocumentRequest(@NotBlank String name, @NotNull DocumentCategory category,
                                    @NotNull VisibilityTier visibilityTier,
                                    UUID targetDepartmentId, String targetContactLabel,
                                    UUID ownerContactId, Instant expiresAt) {}
