package co.ara.onboarding.sla;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Full replace; field-for-field the same as {@link SlaPolicyView}. */
public record UpdateSlaPolicyRequest(@NotNull @PositiveOrZero Double atRiskDays,
                                     @NotNull @Min(1) Integer escalateAfterOverdueDays) {}
