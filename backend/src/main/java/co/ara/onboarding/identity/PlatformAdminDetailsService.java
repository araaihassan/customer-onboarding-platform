package co.ara.onboarding.identity;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticates vendor-side platform administrators for /api/platform/**.
 *
 * Lives in identity because it reads platform_admin; platform must not depend on a
 * domain module. Not permission-gated, and it cannot be: it runs during
 * authentication, before any actor exists, and is invoked by Spring Security's
 * filter chain rather than by a controller.
 *
 * platform_admin is deliberately NOT tenant-scoped (spec 5.2), so this needs no
 * bound tenant -- which is what lets it authenticate a request that has no tenant
 * in its path.
 */
@Service
public class PlatformAdminDetailsService implements UserDetailsService {

    private final PlatformAdminRepository admins;

    public PlatformAdminDetailsService(PlatformAdminRepository admins) { this.admins = admins; }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        PlatformAdmin admin = admins.findByEmailIgnoreCase(email)
                .filter(PlatformAdmin::isEnabled)
                .orElseThrow(() -> new UsernameNotFoundException("No such platform administrator"));

        return User.withUsername(admin.getEmail())
                .password(admin.getPasswordHash())
                .roles("PLATFORM_ADMIN")
                .build();
    }
}
