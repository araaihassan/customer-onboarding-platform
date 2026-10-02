package co.ara.onboarding.agreement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A full replace of a DRAFT agreement's whole signatory list, in order -- {@code
 * sortOrder} is derived from list position, never accepted from the caller ({@link
 * AgreementService#replaceSignatories}).
 */
public record ReplaceSignatoriesRequest(@NotNull @Size(max = 20) List<@Valid SignatoryRequest> signatories,
                                        long lockVersion) {}
