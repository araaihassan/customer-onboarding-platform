package co.ara.onboarding.programme;

/**
 * A programme has no lifecycle of its own -- no hold, no approval, no engine
 * (see {@link Programme}'s own javadoc). This status exists only because business
 * records are deactivated, never deleted, and DELETE is revoked at the database
 * layer for every table this module owns.
 */
public enum ProgrammeStatus { ACTIVE, INACTIVE }
