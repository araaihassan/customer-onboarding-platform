package co.ara.onboarding.agreement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AgreementSignatureView(UUID id, UUID signatoryId, UUID agreementVersionId, String signedContentSha256,
        LocalDate signedOn, String method, UUID recordedBy, Instant recordedAt, UUID countersignedDocumentVersionId) {}
