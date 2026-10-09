package co.ara.onboarding.workflow;

/**
 * Plan amendment 5: workflow cannot import notification, so publish validation asks through this
 * port -- the CustomerDirectory inversion. notification.TemplateKeysAdapter implements it.
 */
public interface NotificationTemplateKeys {
    /** True when a template with this key exists in the current tenant, active or not. */
    boolean exists(String key);
}
