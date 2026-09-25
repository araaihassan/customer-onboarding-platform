package co.ara.onboarding.agreement;

/**
 * Spec section 4.6: how a signature was captured. MANUAL is the only value this
 * sub-project supports -- {@code agreement_provider_ck} pins the database to the
 * same single value, so adding an e-signature provider later is a migration, not
 * a silent widening.
 */
public enum SignatureProviderKind { MANUAL }
