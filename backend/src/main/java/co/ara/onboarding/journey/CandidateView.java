package co.ara.onboarding.journey;

import java.util.UUID;

/** One case's migration eligibility, with the reason attached when it is not eligible. */
public record CandidateView(UUID caseId, UUID customerId, String currentStageName,
                            boolean eligible, String reason) {}
