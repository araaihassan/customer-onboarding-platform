package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * status is a String, not customer.CustomerStatus: the enum stays in its own module,
 * and branch conditions compare strings anyway. The three ownership ids are here
 * because a case copies them at creation, so its descriptors read their own columns.
 */
public record CustomerFacts(UUID id, String status, String industry, String country,
                            UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId) {}
