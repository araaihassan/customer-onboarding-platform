package co.ara.onboarding.tenancy;

import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) { CURRENT.set(tenantId); }

    public static UUID getRequired() {
        UUID id = CURRENT.get();
        if (id == null) throw new IllegalStateException("No tenant context bound to this thread");
        return id;
    }

    public static UUID getOrNull() { return CURRENT.get(); }

    public static void clear() { CURRENT.remove(); }

    public static void runAs(UUID tenantId, Runnable action) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try { action.run(); } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
