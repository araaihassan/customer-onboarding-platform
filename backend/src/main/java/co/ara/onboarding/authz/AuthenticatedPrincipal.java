package co.ara.onboarding.authz;

import java.util.UUID;

/**
 * The identity carried by an authenticated request.
 *
 * Deliberately holds no permissions. Authority is resolved server-side per
 * request from the role tables (spec 6.7), so a token cannot carry stale or
 * forged authority — the principal says only who you are, never what you may do.
 */
public record AuthenticatedPrincipal(UUID tenantId, UUID userId) {}
