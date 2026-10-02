package co.ara.onboarding.agreement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * "Not in the future" is checked in {@link AgreementSignatureService#record} against
 * {@code LocalDate.now(clock)}, deliberately not with {@code @PastOrPresent}, which reads
 * the JVM default zone (3A Task 1's defect).
 */
public record RecordSignatureRequest(@NotNull UUID signatoryId, @NotNull LocalDate signedOn,
                                     @NotBlank @Size(max = 200) String method, long lockVersion) {}
