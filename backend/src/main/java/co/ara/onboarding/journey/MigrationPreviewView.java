package co.ara.onboarding.journey;

import java.util.List;
import java.util.UUID;

/** The review screen's two headline numbers -- "31 cases on v4 / 18 eligible" -- plus one row per candidate. */
public record MigrationPreviewView(UUID versionId, int onVersion, int eligible, List<CandidateView> candidates) {}
