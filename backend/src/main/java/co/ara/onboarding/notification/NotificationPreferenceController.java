package co.ara.onboarding.notification;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own notification preferences (6B spec 8). */
@RestController
@RequestMapping("/api/t/{tenantSlug}/notifications/preferences")
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferences;

    public NotificationPreferenceController(NotificationPreferenceService preferences) { this.preferences = preferences; }

    @GetMapping
    public PreferencesView get() { return preferences.get(); }

    @PutMapping
    public PreferencesView replace(@Valid @RequestBody UpdatePreferencesRequest request) {
        return preferences.replace(request);
    }
}
