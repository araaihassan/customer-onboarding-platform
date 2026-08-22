package co.ara.onboarding.workflow;

/**
 * Subtractive only: it narrows who may write inside a stage after the permission
 * gate has already said yes. There is no branch here that grants (design spec §6.3).
 */
public enum WriteScope { ANY, DEPARTMENT, TEAM, OWNER_ONLY }
