package co.ara.onboarding.journey;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record MigrateRequest(@NotNull UUID versionId, @NotEmpty List<UUID> caseIds) {}
