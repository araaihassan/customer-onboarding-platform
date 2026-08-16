package co.ara.onboarding.identity;

import co.ara.onboarding.platform.Uuid7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first platform administrator from configuration, if one is
 * configured and does not already exist.
 *
 * This exists because Task 22 secured /api/platform/** behind
 * hasRole("PLATFORM_ADMIN") while nothing anywhere creates a platform_admin row —
 * the table ships empty and only the test fixture ever wrote to it. The result was
 * a bootstrap deadlock: provisioning the first tenant requires an administrator,
 * and creating an administrator required... nothing, because there was no path at
 * all. It is invisible in tests, which seed their own, and fatal on a fresh
 * deployment.
 *
 * Configuration rather than a migration, deliberately: a seeded password in a
 * committed migration is a credential in version control, and migrations are
 * forward-only so it could never be rotated. Both properties are empty by default,
 * so this does nothing unless an operator opts in:
 *
 *   APP_PLATFORM_ADMIN_EMAIL=ops@example.com APP_PLATFORM_ADMIN_PASSWORD=... ./gradlew bootRun
 *
 * Idempotent — an existing administrator with that email is left untouched, so the
 * password is never silently reset by a restart.
 */
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final PlatformAdminRepository admins;
    private final PasswordEncoder passwords;
    private final String email;
    private final String password;

    public PlatformAdminBootstrap(PlatformAdminRepository admins, PasswordEncoder passwords,
                                  @Value("${app.platform-admin.email:}") String email,
                                  @Value("${app.platform-admin.password:}") String password) {
        this.admins = admins;
        this.passwords = passwords;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) return;

        if (admins.findByEmailIgnoreCase(email).isPresent()) {
            log.info("Platform administrator {} already exists; leaving it unchanged", email);
            return;
        }

        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(Uuid7.generate());
        admin.setEmail(email);
        admin.setPasswordHash(passwords.encode(password));
        admin.setFullName(email);
        admin.setEnabled(true);
        admins.save(admin);

        log.info("Created platform administrator {}", email);
    }
}
