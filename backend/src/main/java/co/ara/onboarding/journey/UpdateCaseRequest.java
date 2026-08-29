package co.ara.onboarding.journey;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.UUID;

/**
 * A full replace of everything CaseView exposes as editable -- the name, the
 * ownership triple and the attribute answers. Attributes are re-validated
 * exactly as on create: omitting one from the body is rejected, never
 * treated as "leave it alone", per CLAUDE.md's full-replace invariant.
 *
 * {@code name} is {@code @NotBlank} -- following {@link ReasonRequest}'s own
 * shape -- rather than silently written: unlike create, no caller predates
 * this field on the update path (nothing in the frontend calls PUT
 * .../cases/{id} yet), so there is no compatibility fallback to preserve
 * here, and a 400 from bean validation is clearer than a raw NOT NULL
 * constraint violation. {@link CaseController#update} must stay {@code @Valid}
 * for this to take effect.
 */
public record UpdateCaseRequest(@NotBlank String name, UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId,
                                Map<String, String> attributes) {}
