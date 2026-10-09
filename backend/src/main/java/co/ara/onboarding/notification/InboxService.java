package co.ara.onboarding.notification;

import co.ara.onboarding.authz.AuthContextProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The caller's own inbox (6B spec 7.3). NOT permission-gated, on MeService's stated basis: it only
 * ever touches rows whose recipient_user_id is the caller, and a permission every role must hold
 * is no permission. Every id it accepts is looked up with recipient = caller in the predicate, so
 * another user's id and a cross-tenant id are both 404.
 */
@Service
public class InboxService {

    private final NotificationRepository notifications;
    private final AuthContextProvider contexts;
    private final Clock clock;

    public InboxService(NotificationRepository notifications, AuthContextProvider contexts, Clock clock) {
        this.notifications = notifications;
        this.contexts = contexts;
        this.clock = clock;
    }

    private UUID me() { return contexts.principal().userId(); }

    private Specification<Notification> mine() {
        UUID me = me();
        return (r, q, cb) -> cb.and(cb.equal(r.get("recipientUserId"), me), cb.isTrue(r.get("inApp")));
    }

    /** UUIDv7 ids sort by creation time in Postgres's uuid ordering, which is why the cursor is the id. */
    @Transactional(readOnly = true)
    public InboxPage list(String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        Specification<Notification> spec = mine();
        if (cursor != null && !cursor.isBlank()) {
            UUID after = parseCursor(cursor);
            spec = spec.and((r, q, cb) -> cb.lessThan(r.get("id"), after));
        }
        var page = notifications.findAll(spec, PageRequest.of(0, limit + 1, Sort.by(Sort.Direction.DESC, "id")));
        var rows = page.getContent();
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(InboxService::view).toList();
        String next = more ? items.get(items.size() - 1).id().toString() : null;
        return new InboxPage(items, unreadCount(), next);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return notifications.count(mine().and((r, q, cb) -> cb.isNull(r.get("readAt"))));
    }

    @Transactional
    public void markRead(UUID id) {
        Notification n = notifications.findOne(mine().and((r, q, cb) -> cb.equal(r.get("id"), id)))
                .orElseThrow(() -> new NoSuchElementException("Not found"));
        if (n.getReadAt() == null) n.setReadAt(Instant.now(clock));
    }

    @Transactional
    public int markAllRead() { return notifications.markAllRead(me(), Instant.now(clock)); }

    private static UUID parseCursor(String cursor) {
        try { return UUID.fromString(cursor); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Malformed cursor"); }
    }

    static NotificationView view(Notification n) {
        return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLinkPath(), n.getTone(),
                n.getReadAt() != null, n.getCreatedAt());
    }
}
