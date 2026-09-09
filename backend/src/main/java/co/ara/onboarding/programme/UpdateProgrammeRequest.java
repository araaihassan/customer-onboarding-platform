package co.ara.onboarding.programme;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * A full replace of everything about a programme except its customer (fixed at
 * creation -- a programme groups ONE customer's journeys, QA Q20) and its status
 * (its own {@code /deactivate} endpoint, never a plain field on this request).
 * ProgrammeView must carry every field here -- a field absent from the body
 * deserialises to null and is written as null on the next PUT, per CLAUDE.md's
 * full-replace invariant. ProgrammeServiceTest.
 * updateIsAFullReplaceAndTheViewCarriesEveryFieldTheRequestAccepts proves the
 * alignment by reflection over both records' components.
 */
public record UpdateProgrammeRequest(@NotBlank String name, String description,
                                     UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId) {}
