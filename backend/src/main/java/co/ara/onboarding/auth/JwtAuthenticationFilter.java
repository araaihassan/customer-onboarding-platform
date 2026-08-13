package co.ara.onboarding.auth;

import co.ara.onboarding.platform.RequestAuditContext;
import co.ara.onboarding.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Populates the SecurityContext from the Authorization: Bearer header.
 *
 * Registered INSIDE Spring Security's filter chain by SecurityConfig, not as an
 * ordered servlet-filter bean. Spring Security's FilterChainProxy sits at order
 * -100, so an @Order(20) bean would run after the chain's authorization check has
 * already rejected the request — every authenticated endpoint would 401 no matter
 * what token was presented. SecurityConfig also disables its automatic servlet
 * registration so it runs exactly once, in one place.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokens;
    private final ObjectProvider<RequestAuditContext> auditContext;

    public JwtAuthenticationFilter(TokenService tokens,
                                   ObjectProvider<RequestAuditContext> auditContext) {
        this.tokens = tokens;
        this.auditContext = auditContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            tokens.parse(header.substring(7)).ifPresent(principal -> {
                // A token is only valid for the tenant it was issued for. Without this
                // check a valid token would authenticate against any tenant's path --
                // the whole isolation model would rest on the URL alone. Task 22's
                // negative suite depends on it.
                if (principal.tenantId().equals(TenantContext.getOrNull())) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
                    // Until now every audit event was actorType SYSTEM with a null actor;
                    // this is what starts attributing them to a user.
                    auditContext.getObject().setActor(
                            principal.userId(), RequestAuditContext.ActorType.USER);
                }
            });
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
