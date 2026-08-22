package co.ara.onboarding.journey;

import java.util.Map;
import java.util.UUID;

/**
 * A full replace of everything CaseView exposes as editable -- the ownership
 * triple and the attribute answers. Attributes are re-validated exactly as on
 * create: omitting one from the body is rejected, never treated as "leave it
 * alone", per CLAUDE.md's full-replace invariant.
 */
public record UpdateCaseRequest(UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId,
                                Map<String, String> attributes) {}
