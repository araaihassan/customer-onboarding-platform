package co.ara.onboarding.document;

import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * Task 17: the metadata-update and retargeting path (design spec Sec 6.4 --
 * "rename, recategorise, retarget"), gated {@code document.manage}.
 *
 * {@code PATCH}, not {@code PUT} -- a partial update. Every field is
 * nullable, and only a SUPPLIED (non-null) field changes; a field the caller
 * leaves out (or sends {@code null}) is left exactly as it was, never
 * blanked. This is deliberately narrower than a full JSON-merge-patch: there
 * is no way to use this request to CLEAR {@code targetDepartmentId} or
 * {@code targetContactLabel} back to {@code null} once set -- only to point
 * either at a different, non-null value. Nothing in this task needs that
 * (the recovery path Sec 6.4 exists for is "retarget to a DIFFERENT,
 * populated department", never "untarget"), and this codebase has no
 * existing convention for a three-state absent/null/value field (no
 * {@code Optional<T>} request field exists anywhere else) -- inventing one
 * speculatively, for a need nobody has yet, is not worth the complexity. If
 * a real need to clear a target surfaces later, this is the place to add it.
 *
 * {@code name}/{@code category} are the plain rename/recategorise half.
 * {@code targetDepartmentId} is resolved through {@code customer.OrgUnitResolver}
 * before being written, the same existing component {@link CreateDocumentRequest}
 * already uses for the identical field, so a bogus department id 404s rather
 * than surfacing a raw FK violation. {@code targetContactLabel} is free text,
 * exactly as it is on create -- there is no catalog of valid labels to
 * validate against (design spec Sec 4.6).
 *
 * Retargeting -- {@code targetDepartmentId} or {@code targetContactLabel}
 * actually CHANGING value, not merely being re-supplied with the value the
 * document already has -- is audited as its own action,
 * {@code document.retargeted}, never folded into a generic update: see
 * {@link DocumentService#patch}'s own javadoc.
 *
 * {@code name}, when supplied, carries the same control-character/double-quote
 * restriction {@link CreateDocumentRequest#name} does (Task 22 review Finding
 * 2 -- see that field's own javadoc for the empirical evidence); {@code
 * @Pattern} passes a null field through untouched, so this adds no new
 * requirement on top of the class's own "every field is optional" contract.
 */
public record PatchDocumentRequest(
        @Pattern(regexp = CreateDocumentRequest.NAME_PATTERN, message = CreateDocumentRequest.NAME_MESSAGE) String name,
        DocumentCategory category, UUID targetDepartmentId, String targetContactLabel) {}
