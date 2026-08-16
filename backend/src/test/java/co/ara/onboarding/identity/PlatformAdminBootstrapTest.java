package co.ara.onboarding.identity;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bootstrap that breaks the platform-admin deadlock: /api/platform/** requires
 * a platform administrator, and until this existed nothing in production code
 * could create one.
 */
class PlatformAdminBootstrapTest extends PostgresTestBase {

    @Autowired PlatformAdminRepository admins;
    @Autowired PasswordEncoder passwords;

    private PlatformAdminBootstrap bootstrapWith(String email, String password) {
        return new PlatformAdminBootstrap(admins, passwords, email, password);
    }

    @Test
    void createsTheConfiguredAdministrator() {
        bootstrapWith("bootstrap@vendor.example", "a-long-bootstrap-password").run(null);

        var created = admins.findByEmailIgnoreCase("bootstrap@vendor.example");
        assertThat(created).isPresent();
        assertThat(created.get().isEnabled()).isTrue();
        assertThat(passwords.matches("a-long-bootstrap-password", created.get().getPasswordHash()))
                .as("the password must be hashed with the same encoder login verifies against")
                .isTrue();
    }

    /**
     * Idempotent, and specifically it must not reset the password. A restart
     * silently reverting an administrator's rotated credential back to whatever is
     * in the environment would be worse than not running at all.
     */
    @Test
    void leavesAnExistingAdministratorUntouched() {
        bootstrapWith("existing@vendor.example", "original-password").run(null);
        String originalHash = admins.findByEmailIgnoreCase("existing@vendor.example")
                .orElseThrow().getPasswordHash();

        bootstrapWith("existing@vendor.example", "a-different-password").run(null);

        assertThat(admins.findByEmailIgnoreCase("existing@vendor.example").orElseThrow()
                .getPasswordHash())
                .isEqualTo(originalHash);
    }

    /** Unconfigured is the default, and must be a no-op rather than a blank account. */
    @Test
    void doesNothingWhenUnconfigured() {
        long before = admins.count();

        bootstrapWith("", "").run(null);
        bootstrapWith("someone@vendor.example", "").run(null);
        bootstrapWith("", "a-password").run(null);

        assertThat(admins.count()).isEqualTo(before);
    }
}
