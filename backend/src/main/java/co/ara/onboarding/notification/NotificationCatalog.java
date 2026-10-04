package co.ara.onboarding.notification;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import static co.ara.onboarding.notification.NotificationType.*;

/**
 * Spec Â§5.1: every type, its preferences-pane label and its defaults. Defaults come from the
 * prototype's channel captions; ESCALATION is the one locked type (QA Q10).
 */
public final class NotificationCatalog {

    public record Entry(NotificationType type, String label, boolean inAppDefault, boolean emailDefault,
                        boolean locked) {}

    private static final Map<NotificationType, Entry> ENTRIES = new EnumMap<>(NotificationType.class);

    static {
        put(ESCALATION,           "Escalation to you",             true, true,  true);
        put(TASK_ASSIGNED,        "Task assigned to me",           true, true,  false);
        put(TASK_OVERDUE,         "Task overdue",                  true, true,  false);
        put(NEW_CUSTOMER,         "Customer assigned to me",       true, false, false);
        put(MILESTONE_COMPLETED,  "Milestone completed",           true, false, false);
        put(STAGE_CHANGED,        "Stage entered or exited",       true, false, false);
        put(DOCUMENT_REQUESTED,   "Document requested",            true, true,  false);
        put(DOCUMENT_UPLOADED,    "Document uploaded",             true, true,  false);
        put(DOCUMENT_DECIDED,     "Document approved or rejected", true, true,  false);
        put(AGREEMENT_STATUS,     "Agreement status changed",      true, true,  false);
        put(NEW_COMMENT,          "New comment",                   true, true,  false);
        put(WORKFLOW_PUBLISHED,   "Workflow version published",    true, false, false);
        put(RISK_CHANGED,         "Journey at risk or breached",   true, true,  false);
        put(DEADLINE_APPROACHING, "Deadline approaching",          true, false, false);
        put(EXPIRY_RENEWAL,       "Expiry and renewal",            true, true,  false);
    }

    private static void put(NotificationType t, String label, boolean inApp, boolean email, boolean locked) {
        ENTRIES.put(t, new Entry(t, label, inApp, email, locked));
    }

    private NotificationCatalog() {}

    public static Entry of(NotificationType type) { return ENTRIES.get(type); }

    public static List<Entry> all() { return Arrays.stream(NotificationType.values()).map(ENTRIES::get).toList(); }

    public static List<Entry> optOut() { return all().stream().filter(e -> !e.locked()).toList(); }
}
