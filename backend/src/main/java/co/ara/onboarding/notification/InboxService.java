package co.ara.onboarding.notification;

import co.ara.onboarding.authz.AuthContextProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The caller's own inbox (6B spec 7.3). NOT permission-gated, on MeService's stated basis: it only
 * ever touches rows whose recipient_user_id is the caller, and a permission every role must hold
 * is no permission. That is also why it is a class-wide AuthorizationCoverageTest exclusion, and
 * the exclusion is safe only while EVERY query here composes {@link #mine()} (recipient = caller,
 * in-app only; RLS binds the tenant underneath) -- a new query that does not is a cross-user read.
 * Every id it accepts is looked up with recipient = caller in the predicate, so another user's id
 * and a cross-tenant id are both 404.
 *
 * <p>A row's stored title and body can name a record, so each row is also re-checked against the
 * caller's CURRENT access ({@link NotificationVisibility}, the digest's own mapping) before it is
 * listed, counted or marked read: a revoked grant or a lost team hides it on the next call, and
 * marking a hidden row read is a 404 like any other out-of-scope id. The one exception is an
 * ESCALATION, which the pipeline never gated (spec 5.3 step 5: escalation has no off switch).
 *
 * <p>Paging scans newest-first in batches until the page is full or {@link #MAX_SCAN} raw rows have
 * been read, and the cursor is the last row SCANNED (not the last shown), so hidden rows never strand
 * a cursor: each call moves it forward, and it is null only once nothing older remains. The unread
 * count is computed through the same filter and stops at {@link #COUNT_CAP} (the badge shows "99+"
 * above 99) or {@link #MAX_SCAN} unread rows, so it can under-count a backlog of hidden rows but never
 * advertises a row the list would hide.
 */
@Service
public class InboxService {

    static final int MAX_SCAN = 500;
    static final int COUNT_CAP = 100;
    private static final int BATCH = 50;

    private final NotificationRepository notifications;
    private final AuthContextProvider contexts;
    private final Clock clock;
    private final NotificationVisibility visibility;

    public InboxService(NotificationRepository notifications, AuthContextProvider contexts, Clock clock,
                        NotificationVisibility visibility) {
        this.notifications = notifications;
        this.contexts = contexts;
        this.clock = clock;
        this.visibility = visibility;
    }

    private UUID me() { return contexts.principal().userId(); }

    private Specification<Notification> mine() {
        UUID me = me();
        return (r, q, cb) -> cb.and(cb.equal(r.get("recipientUserId"), me), cb.isTrue(r.get("inApp")));
    }

    private static Specification<Notification> olderThan(UUID id) {
        return (r, q, cb) -> cb.lessThan(r.get("id"), id);
    }

    /** UUIDv7 ids sort by creation time in Postgres's uuid ordering, which is why the cursor is the id. */
    @Transactional(readOnly = true)
    public InboxPage list(String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        UUID after = cursor == null || cursor.isBlank() ? null : parseCursor(cursor);
        Gate gate = new Gate(me());
        List<NotificationView> items = new ArrayList<>();
        UUID lastScanned = null;
        int scanned = 0;
        boolean exhausted = false;
        scan:
        while (items.size() < limit && scanned < MAX_SCAN) {
            Specification<Notification> spec = after == null ? mine() : mine().and(olderThan(after));
            var rows = notifications.findAll(spec, PageRequest.of(0, BATCH, Sort.by(Sort.Direction.DESC, "id")))
                    .getContent();
            for (Notification n : rows) {
                scanned++;
                lastScanned = n.getId();
                if (gate.shows(n)) items.add(view(n));
                if (items.size() == limit || scanned == MAX_SCAN) break scan;
            }
            if (rows.size() < BATCH) { exhausted = true; break; }
            after = lastScanned;
        }
        boolean more = !exhausted && lastScanned != null && notifications.exists(mine().and(olderThan(lastScanned)));
        return new InboxPage(items, unreadCount(gate), more ? lastScanned.toString() : null);
    }

    @Transactional(readOnly = true)
    public long unreadCount() { return unreadCount(new Gate(me())); }

    private long unreadCount(Gate gate) {
        Specification<Notification> unread = mine().and((r, q, cb) -> cb.isNull(r.get("readAt")));
        long visible = 0;
        int scanned = 0;
        UUID after = null;
        while (visible < COUNT_CAP && scanned < MAX_SCAN) {
            Specification<Notification> spec = after == null ? unread : unread.and(olderThan(after));
            var rows = notifications.findAll(spec, PageRequest.of(0, BATCH, Sort.by(Sort.Direction.DESC, "id")))
                    .getContent();
            for (Notification n : rows) {
                scanned++;
                after = n.getId();
                if (gate.shows(n) && ++visible == COUNT_CAP) break;
                if (scanned == MAX_SCAN) break;
            }
            if (rows.size() < BATCH) break;
        }
        return visible;
    }

    @Transactional
    public void markRead(UUID id) {
        Notification n = notifications.findOne(mine().and((r, q, cb) -> cb.equal(r.get("id"), id)))
                .filter(new Gate(me())::shows)
                .orElseThrow(() -> new NoSuchElementException("Not found"));
        if (n.getReadAt() == null) n.setReadAt(Instant.now(clock));
    }

    /** Marks every own unread in-app row, hidden ones included: harmless, and the count never showed them. */
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

    /** The caller's current-access check, memoised per call so rows about the same subject cost one lookup. */
    private final class Gate {
        private final UUID userId;
        private final Map<String, Boolean> seen = new HashMap<>();

        Gate(UUID userId) { this.userId = userId; }

        boolean shows(Notification n) {
            if (n.getType() == NotificationType.ESCALATION) return true;
            String key = n.getSubjectType() + ":" + n.getSubjectId() + ":" + n.getCaseId();
            return seen.computeIfAbsent(key, k ->
                    visibility.visibleNow(userId, n.getSubjectType(), n.getSubjectId(), n.getCaseId()));
        }
    }
}
