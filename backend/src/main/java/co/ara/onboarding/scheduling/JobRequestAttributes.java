package co.ara.onboarding.scheduling;

import org.springframework.web.context.request.RequestAttributes;
import java.util.*;

/**
 * A plain in-memory RequestAttributes, so request-scoped beans (AuthorizationService,
 * RequestAuditContext) resolve inside a scheduled job exactly as inside a request -- one fresh
 * instance per tenant run, discarded afterwards (spec §1.2.3).
 */
final class JobRequestAttributes implements RequestAttributes {
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, Runnable> destructionCallbacks = new LinkedHashMap<>();

    @Override public Object getAttribute(String name, int scope) { return attributes.get(name); }
    @Override public void setAttribute(String name, Object value, int scope) { attributes.put(name, value); }
    @Override public void removeAttribute(String name, int scope) { attributes.remove(name); }
    @Override public String[] getAttributeNames(int scope) { return attributes.keySet().toArray(String[]::new); }
    @Override public void registerDestructionCallback(String name, Runnable callback, int scope) { destructionCallbacks.put(name, callback); }
    @Override public Object resolveReference(String key) { return null; }
    @Override public String getSessionId() { return "scheduled-job"; }
    @Override public Object getSessionMutex() { return this; }

    void complete() { destructionCallbacks.values().forEach(Runnable::run); destructionCallbacks.clear(); }
}
