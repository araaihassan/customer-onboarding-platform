package co.ara.onboarding.programme;

/**
 * Programme's own participant lifecycle -- deliberately NOT
 * {@code journey.ParticipantStatus}, even though the concept is similar, so a
 * later change to journey's participant lifecycle cannot silently change
 * programme's.
 */
public enum ProgrammeParticipantStatus { ACTIVE, REMOVED }
