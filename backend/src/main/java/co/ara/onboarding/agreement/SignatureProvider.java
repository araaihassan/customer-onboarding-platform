package co.ara.onboarding.agreement;

import java.util.Optional;

/**
 * Spec 3.4/4.6: how an approved agreement's version is sent out for signature.
 * {@link AgreementService#send} selects among the Spring-collected beans of this
 * interface by {@link Agreement#getSignatureProvider()}, throwing {@link
 * IllegalStateException} if none matches -- the column selects the provider, and
 * {@code agreement_provider_ck} currently permits only {@link SignatureProviderKind#MANUAL},
 * so {@link ManualSignatureProvider} is the only implementation today.
 */
public interface SignatureProvider {

    SignatureProviderKind kind();

    /** Spec 3.4. Returns the provider's envelope id, if it creates one. MANUAL creates none. */
    Optional<String> send(Agreement agreement, AgreementVersion sentVersion);
}
