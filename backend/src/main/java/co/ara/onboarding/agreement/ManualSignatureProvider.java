package co.ara.onboarding.agreement;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The only {@link SignatureProvider} (spec 3.4). {@code send()} does nothing: signatures
 * arrive through {@code AgreementSignatureService.record}, entered by staff. The OpenSign
 * adapter is NOT implemented -- deliberately deferred; see CLAUDE.md "What sub-project 5
 * inherits".
 */
@Component
public class ManualSignatureProvider implements SignatureProvider {
    @Override public SignatureProviderKind kind() { return SignatureProviderKind.MANUAL; }
    @Override public Optional<String> send(Agreement agreement, AgreementVersion sentVersion) { return Optional.empty(); }
}
