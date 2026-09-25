package co.ara.onboarding.agreement;

import java.util.List;

/**
 * {@code versions} is newest-first ({@link AgreementVersionRepository#ofAgreementNewestFirst}),
 * {@code signatures} is oldest-first ({@link AgreementSignatureRepository#ofAgreement}) --
 * each list keeps its own repository's own natural order rather than being
 * re-sorted here.
 */
public record AgreementDetailView(AgreementView agreement, List<AgreementSignatoryView> signatories,
        List<AgreementVersionView> versions, List<AgreementSignatureView> signatures) {}
