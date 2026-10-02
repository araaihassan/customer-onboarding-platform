package co.ara.onboarding.agreement;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Set;

/**
 * PATCH: a null field is left unchanged (PatchDocumentRequest's convention). Unlike that
 * request, a draft's dates must be removable -- a wrongly entered expiry cannot be left
 * stuck -- so {@code clear} names fields to set back to null. A field both supplied and
 * cleared is a 400 ({@link AgreementService#patch}).
 *
 * <p>{@code name}'s pattern is the same control-character/double-quote restriction
 * {@code document.CreateDocumentRequest#name} carries, applied here as its own literal
 * rather than a cross-module reference -- {@code agreement} names no {@code document}
 * request type.
 */
public record PatchAgreementRequest(
        @Size(max = 200) @Pattern(regexp = "^[^\\x00-\\x1F\\x7F\"]*$") String name,
        LocalDate effectiveDate, LocalDate expiresAt, LocalDate renewalDate,
        @PositiveOrZero Integer noticePeriodDays,
        Set<ClearableAgreementField> clear,
        long lockVersion) {}
