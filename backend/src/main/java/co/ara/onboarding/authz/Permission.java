package co.ara.onboarding.authz;

import java.util.Set;

/**
 * One entry in the code-defined catalog.
 *
 * {@code resourceType} is null for ALL-only permissions, which have no existing
 * record to scope against. Any permission with a non-null resourceType must have
 * a registered ResourceAuthorizationDescriptor (Task 11) or the application
 * refuses to start -- that startup check is what keeps a scoped permission from
 * silently behaving as unscoped because nobody wrote its predicate.
 */
public record Permission(String key, String category, String resourceType,
                         String description, Set<Scope> allowedScopes) {}
