package co.ara.onboarding.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * A full replace (6B spec 8, plan amendment 11): every opt-out type must be listed exactly once;
 * ESCALATION may be listed only with both channels on. Field-for-field aligned with {@link PreferencesView}.
 */
public record UpdatePreferencesRequest(@NotNull EmailCadence emailCadence,
                                       @NotNull @Valid List<@NotNull @Valid TypePreferenceRequest> types) {}
