package co.ara.onboarding.agreement;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A reviewer's decision on an agreement's latest submitted version (spec 4.5/5.3).
 * {@code reason} is optional on APPROVE and required on REJECT -- {@link
 * AgreementReviewService#review} enforces the latter as a 400 ({@link
 * IllegalArgumentException}), since a single {@code @NotBlank} cannot be conditional on
 * a sibling field the way {@code PatchAgreementRequest}'s own javadoc already explains
 * for a different pair of fields.
 */
public record ReviewAgreementRequest(@NotNull ReviewDecision decision, @Size(max = 2000) String reason,
        long lockVersion) {}
