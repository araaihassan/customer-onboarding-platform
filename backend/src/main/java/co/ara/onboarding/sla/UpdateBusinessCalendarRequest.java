package co.ara.onboarding.sla;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Full replace of the calendar's own fields. Its components are a subset of
 * {@link BusinessCalendarView}'s: {@code holidays} are managed by their own routes (add / remove),
 * so the view carries them and this request deliberately does not -- a PUT never touches them.
 * {@code workingDays} are ISO day-of-week numbers, 1 = Monday .. 7 = Sunday.
 */
public record UpdateBusinessCalendarRequest(@NotBlank String name, @NotBlank String timezone,
                                            @NotEmpty List<Integer> workingDays) {}
