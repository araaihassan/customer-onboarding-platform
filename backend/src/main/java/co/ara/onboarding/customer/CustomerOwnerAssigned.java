package co.ara.onboarding.customer;

import java.util.UUID;

/**
 * Published, in the acting transaction, after the audit record and after the customer row is
 * flushed: a customer was given an owner (on create, or when update changed it).
 */
public record CustomerOwnerAssigned(UUID customerId, UUID ownerUserId, UUID actorId) {}
