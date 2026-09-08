package co.ara.onboarding.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {

    /**
     * A SEPARATE chain for /api/platform/**, ordered ahead of the main one.
     *
     * These endpoints are vendor-side and operated by hand, so HTTP Basic against
     * platform_admin is sufficient. Keeping them in their own chain is what stops
     * httpBasic() installing a BasicAuthenticationEntryPoint as the default for the
     * whole application — which would put a WWW-Authenticate header on every API
     * 401 and make browsers show a native credential prompt over the SPA.
     *
     * The UserDetailsService is injected by interface, so platform does not import
     * identity, where the implementation lives.
     */
    @Bean
    @Order(1)
    SecurityFilterChain platformFilterChain(HttpSecurity http,
                                            UserDetailsService platformAdmins) throws Exception {
        return http
            .securityMatcher("/api/platform/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .userDetailsService(platformAdmins)
            .httpBasic(Customizer.withDefaults())
            .authorizeHttpRequests(a -> a.anyRequest().hasRole("PLATFORM_ADMIN"))
            .build();
    }

    /**
     * jwtFilter is injected by qualifier as OncePerRequestFilter rather than by its
     * concrete type so platform does not import auth — auth depends on platform
     * (RequestAuditContext), so naming the concrete type here would close a cycle
     * that ModuleBoundaryTest rejects.
     */
    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @Qualifier("jwtAuthenticationFilter") OncePerRequestFilter jwtFilter)
            throws Exception {
        return http
            .csrf(csrf -> csrf.disable())   // stateless bearer tokens; refresh cookie is SameSite=Strict
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Placed before the username/password filter so the SecurityContext is
            // populated well before AuthorizationFilter evaluates the rules below.
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            // 401 for "not authenticated", not Spring Security's default 403.
            // With no entry point configured it installs Http403ForbiddenEntryPoint,
            // so a missing or expired bearer token answers 403 — and Task 24's API
            // client refreshes on 401 only, so it would never retry and the user
            // would be silently logged out. 403 remains correct for an authenticated
            // caller who lacks a permission; that path uses the AccessDeniedHandler.
            .exceptionHandling(e -> e.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/t/*/auth/login",
                                 "/api/t/*/auth/refresh",
                                 "/api/t/*/auth/activate",
                                 "/api/t/*/auth/password-reset/**").permitAll()
                // The frontend generates its types from this document, and Task 19's
                // OpenApiDocumentTest reads it. It describes endpoint shapes, not data.
                // TODO(task-22): decide whether to expose it outside dev — springdoc
                // can be disabled per profile rather than secured here.
                .requestMatchers("/v3/api-docs/**", "/v3/api-docs").permitAll()
                // Boot's default handling of a framework-level exception this app has
                // no @ExceptionHandler for (bean validation, malformed JSON, an
                // unmapped route, ...) calls response.sendError(), which the servlet
                // container turns into an internal forward to /error — a SECOND pass
                // through this whole chain. jwtFilter is a plain OncePerRequestFilter,
                // which skips itself on that dispatch by Spring's own default
                // (shouldNotFilterErrorDispatch()), so the SecurityContext is empty on
                // the forward. Without this line, .anyRequest().authenticated() then
                // rejects THAT pass and the entry point below overwrites the real
                // status (e.g. 400 from a validation failure) with 401 — a fully
                // authenticated, fully authorized request whose body merely fails
                // validation becomes indistinguishable from an expired session, which
                // is exactly the signal the frontend's silent-refresh logic acts on.
                // /error needs no authentication of its own: BasicErrorController
                // reads the status the container already recorded and does not touch
                // tenant or user data.
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated())
            .build();
    }

    /**
     * Suppresses the automatic servlet-chain registration of the JWT filter. Without
     * this, a @Component filter is registered both by Boot and by the security chain
     * above. OncePerRequestFilter would dedupe it, so the symptom would not be a
     * double execution but an ordering question nobody had answered — the copy that
     * runs first wins. One registration, one place.
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> jwtFilterNotRegisteredGlobally(
            @Qualifier("jwtAuthenticationFilter") OncePerRequestFilter jwtFilter) {
        var registration = new FilterRegistrationBean<>(jwtFilter);
        registration.setEnabled(false);
        return registration;
    }
}
