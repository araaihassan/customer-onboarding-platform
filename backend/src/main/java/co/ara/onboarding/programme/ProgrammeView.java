package co.ara.onboarding.programme;

import java.util.UUID;

/**
 * Carries every field {@link UpdateProgrammeRequest} accepts (name, description,
 * the ownership triple) plus the fields that never round-trip through a PUT:
 * {@code id}, the fixed {@code customerId}, a display-only {@code customerName},
 * and {@code status} (set only by {@code /deactivate}).
 *
 * {@code customerName} is resolved by {@link ProgrammeService} through
 * {@code AuthorizedQuery} against {@code customer.Customer} under
 * {@code customer.view}, not through {@code journey.CustomerDirectory} --
 * see ProgrammeService's own doc comment for why the facts port this module
 * otherwise follows (the same inversion journey.CaseService uses) cannot supply
 * this one field.
 */
public record ProgrammeView(UUID id, String name, UUID customerId, String customerName,
                            String description, UUID ownerUserId, UUID owningDepartmentId,
                            UUID owningTeamId, ProgrammeStatus status) {}
