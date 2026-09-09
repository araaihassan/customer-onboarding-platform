package co.ara.onboarding.programme;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * customerId is resolved through AuthorizedQuery before anything is written --
 * ProgrammeService.create's own doc comment names this -- so a foreign or
 * out-of-scope customer id fails closed as a 404 rather than creating a
 * programme against a customer the caller cannot see. Unlike CreateCaseRequest,
 * a programme carries no template to pin: it is a container, not a runtime
 * (QA Q20's "no lifecycle of its own").
 */
public record CreateProgrammeRequest(@NotBlank String name, @NotNull UUID customerId,
                                     String description, UUID ownerUserId,
                                     UUID owningDepartmentId, UUID owningTeamId) {}
