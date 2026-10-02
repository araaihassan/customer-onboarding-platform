package co.ara.onboarding.agreement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cancels a live agreement; the successor DRAFT is created in the same transaction (spec 5.5). */
public record CancelAgreementRequest(@NotBlank @Size(max = 2000) String reason, long lockVersion) {}
