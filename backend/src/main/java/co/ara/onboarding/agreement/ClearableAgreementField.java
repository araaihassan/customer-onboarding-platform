package co.ara.onboarding.agreement;

/**
 * The four {@link Agreement} fields {@link PatchAgreementRequest#clear} may name to set
 * back to {@code null} -- {@code name} is deliberately absent, since a draft's name is
 * never blank-able (the same {@code @NotBlank}-shaped invariant every other named entity
 * in this codebase carries).
 */
public enum ClearableAgreementField { EFFECTIVE_DATE, EXPIRES_AT, RENEWAL_DATE, NOTICE_PERIOD_DAYS }
