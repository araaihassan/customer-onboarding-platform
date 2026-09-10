package co.ara.onboarding.workflow;

/**
 * The outcome half of {@link DecidePlanRequest} -- gate 1 of QA Q22. There is no
 * third value: a decision is one-shot (see {@link PlanShapeService#decide}), and
 * "still pending" is represented by the absence of a decided row, not a value of
 * this enum.
 */
public enum PlanDecision { APPROVED, REJECTED }
