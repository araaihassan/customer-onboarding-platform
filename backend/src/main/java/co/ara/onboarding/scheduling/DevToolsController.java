package co.ara.onboarding.scheduling;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Dev profile only; not present in any other profile. Accepts no tenant id: the caller's own tenant is used. */
@RestController
@Profile("dev")
@RequestMapping("/api/t/{tenantSlug}/dev")
public class DevToolsController {

    public record OffsetRequest(long seconds) {}

    private final DevToolsService service;

    public DevToolsController(DevToolsService service) { this.service = service; }

    @PostMapping("/clock/offset")
    public Map<String, Long> shift(@RequestBody OffsetRequest request) {
        return Map.of("offsetSeconds", service.shiftClock(request.seconds()));
    }

    @PostMapping("/jobs/sla-sweep")
    public Map<String, Boolean> sweep() {
        return Map.of("ran", service.runSweep());
    }

    @PostMapping("/jobs/notification-sweep")
    public Map<String, Boolean> notificationSweep() {
        return Map.of("ran", service.runNotificationSweep());
    }

    @PostMapping("/jobs/digest")
    public Map<String, Integer> digest() {
        return Map.of("queued", service.runDigest());
    }

    @PostMapping("/jobs/email-dispatch")
    public Map<String, Integer> emailDispatch() {
        return Map.of("sent", service.runEmailDispatch());
    }
}
