package co.ara.onboarding.platform;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The guard that refuses to start the application when DB_APP_PASSWORD is not
 * set to something real.
 *
 * V2__app_role_and_tenant.sql creates the onboarding_app login role with the
 * committed literal password 'onboarding_app', guarded IF NOT EXISTS -- so on a
 * database that already has the role (every environment past the very first
 * migration run), that literal is exactly what a deployment that never set
 * DB_APP_PASSWORD would still be able to authenticate with, the same failure
 * shape JwtProperties was built to prevent for JWT_SECRET.
 *
 * This guard only refuses a blank or denylisted value -- it does not, unlike
 * JwtProperties, enforce a minimum length, because there is no cryptographic
 * minimum here: this is a database login password, not a signing key subject to
 * HMAC-SHA256's 256-bit requirement.
 *
 * Reconciling the role's actual password to match this value is
 * AppRolePasswordReconciler's job, not this class's -- this guard only decides
 * whether the configured value is fit to reconcile to and to connect with. It
 * runs whether or not anything injects it, for the same reason JwtProperties
 * does: a Spring singleton is instantiated eagerly at context refresh.
 *
 * Deliberately NOT keyed on the active profile, for the same reason
 * JwtProperties is not: a deployment that forgot to set a profile is exactly
 * the deployment that forgot to set this too.
 */
@Component
public class DatabaseCredentialsGuard {

    /**
     * The literal password V2__app_role_and_tenant.sql creates the role with,
     * and application.yml's former default. Migrations are forward-only, so this
     * value cannot be removed from the database side -- it is published
     * configuration, not key material, and denylisting it is the only fix
     * available on the application side.
     */
    private static final Set<String> PUBLISHED_PLACEHOLDERS = Set.of("onboarding_app");

    private static final String REMEDY =
            " Set the DB_APP_PASSWORD environment variable to a real password"
                    + " (for example: openssl rand -base64 48). The application will not start"
                    + " without one, on any profile -- it will be reconciled onto the"
                    + " onboarding_app role automatically on every startup.";

    @Value("${DB_APP_PASSWORD:}")
    private String password;

    @PostConstruct
    public void validate() {
        requireUsablePassword(password);
    }

    /**
     * Never includes the rejected value in the message, for the same reason
     * JwtProperties.requireUsableSecret does not: a startup failure is written to
     * stdout and to whatever collects it.
     */
    static void requireUsablePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("DB_APP_PASSWORD is not set." + REMEDY);
        }
        if (PUBLISHED_PLACEHOLDERS.contains(password)) {
            throw new IllegalStateException(
                    "DB_APP_PASSWORD is the literal password published in this repository's own"
                            + " V2__app_role_and_tenant.sql migration, so anyone who can read the"
                            + " source can connect to the database as onboarding_app." + REMEDY);
        }
    }

    public String getPassword() {
        return password;
    }
}
