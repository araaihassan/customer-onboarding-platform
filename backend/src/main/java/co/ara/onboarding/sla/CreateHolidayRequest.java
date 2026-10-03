package co.ara.onboarding.sla;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateHolidayRequest(@NotNull LocalDate date, @NotBlank String name) {}
