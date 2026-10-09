package co.ara.onboarding.notification;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** The caller's own inbox (6B spec 7.3). */
@RestController
@RequestMapping("/api/t/{tenantSlug}/notifications")
public class InboxController {

    private final InboxService inbox;

    public InboxController(InboxService inbox) { this.inbox = inbox; }

    @GetMapping
    public InboxPage list(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") int limit) {
        return inbox.list(cursor, limit);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() { return Map.of("unreadCount", inbox.unreadCount()); }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID id) { inbox.markRead(id); }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll() { return Map.of("marked", inbox.markAllRead()); }
}
