package co.ara.onboarding.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
     * jwtFilter is injected by qualifier as OncePerRequestFilter rather than by its
     * concrete type so platform does not import auth — auth depends on platform
     * (RequestAuditContext), so naming the concrete type here would close a cycle
     * that ModuleBoundaryTest rejects.
     */
    @Bean
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
                .requestMatchers("/api/platform/**").permitAll()   // secured in Task 22
                // The frontend generates its types from this document, and Task 19's
                // OpenApiDocumentTest reads it. It describes endpoint shapes, not data.
                // TODO(task-22): decide whether to expose it outside dev — springdoc
                // can be disabled per profile rather than secured here.
                .requestMatchers("/v3/api-docs/**", "/v3/api-docs").permitAll()
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
