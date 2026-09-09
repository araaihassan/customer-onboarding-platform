package co.ara.onboarding.programme;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The case id comes from a request body, so {@link ProgrammeMembershipService#addJourney}
 * resolves it through {@code AuthorizedQuery} under {@code case.view} before writing the
 * {@code programme_case} link row -- the write-path obligation design spec §6.5 names.
 */
public record AddJourneyRequest(@NotNull UUID caseId) {}
