package co.ara.onboarding.journey;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
 *
 * {@code @Size(max = 160)} matches {@code onboarding_case.name}'s own
 * {@code varchar(160)} (fix round 2) -- see {@link CreateCaseRequest}'s own
 * javadoc for why this guards against a raw 500 rather than a clean 400.
 */
public record UpdateCaseRequest(@NotBlank @Size(max = 160) String name, UUID ownerUserId,
                                UUID owningDepartmentId, UUID owningTeamId,
                                Map<String, String> attributes) {}
