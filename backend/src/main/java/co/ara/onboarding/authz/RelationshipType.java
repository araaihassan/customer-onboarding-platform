package co.ara.onboarding.authz;

/**
 * Relationships that can qualify a record for ASSIGNED scope.
 *
 * ASSIGNED always means a PERSONAL relationship. A record assigned to a team the
 * user belongs to is TEAM scope, never ASSIGNED (spec 6.4) — conflating the two
 * would silently widen ASSIGNED to everything the user's teams can reach.
 */
public enum RelationshipType { OWNER, ASSIGNEE, PARTICIPANT, APPROVER, CREATOR }
