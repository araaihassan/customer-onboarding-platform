package co.ara.onboarding.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AutoRemindView(@NotNull Boolean enabled, @NotNull @Min(1) @Max(30) Integer intervalDays,
                             @NotNull @Min(1) @Max(10) Integer max) {}
