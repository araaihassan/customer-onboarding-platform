package co.ara.onboarding.agreement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One party {@link ReplaceSignatoriesRequest} asks to install as a signatory. Exactly one
 * of {@code contactId}/{@code userId} may be set, matching {@code kind} -- {@link
 * AgreementService#replaceSignatories} refuses a mismatch as a 400 before touching the
 * database, the same {@code agreement_signatory_party_ck} shape the database itself
 * enforces on the row this eventually becomes.
 */
public record SignatoryRequest(@NotNull SignatoryKind kind, UUID contactId, UUID userId,
                               @NotBlank @Size(max = 120) String displayRole) {}
