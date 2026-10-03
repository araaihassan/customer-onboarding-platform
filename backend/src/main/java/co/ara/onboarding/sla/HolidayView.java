package co.ara.onboarding.sla;

import java.time.LocalDate;
import java.util.UUID;

public record HolidayView(UUID id, LocalDate date, String name) {}
