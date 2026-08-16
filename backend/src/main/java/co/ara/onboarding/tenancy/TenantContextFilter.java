package co.ara.onboarding.tenancy;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import java.io.IOException;

@Component
// Must run BEFORE Spring Security's FilterChainProxy, which registers at order
// -100. JwtAuthenticationFilter lives inside that chain and validates a token's
// tid claim against the resolved tenant, so the tenant has to be bound by the
// time the chain runs. At the original @Order(10) this filter ran after the whole
// security chain, TenantContext.getOrNull() was still null, and every bearer token
// was silently rejected.
@Order(-110)
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantResolver resolver;
    private final TenantRepository tenants;
    private final HandlerExceptionResolver exceptionResolver;

    // TenantContextFilter is a plain servlet Filter, so it runs OUTSIDE
    // DispatcherServlet's dispatch: an exception thrown here does not reach
    // @RestControllerAdvice on its own (that machinery only sees exceptions
    // thrown from within a controller's handler execution). Confirmed
    // empirically -- letting UnknownTenantException propagate un-caught
    // produced an unhandled ServletException, not the 404 from
    // ApiExceptionHandler. Resolving it explicitly through the same
    // HandlerExceptionResolver DispatcherServlet itself uses keeps
    // ApiExceptionHandler as the single place that maps exception -> status.
    public TenantContextFilter(TenantResolver resolver, TenantRepository tenants,
                                @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.resolver = resolver;
        this.tenants = tenants;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var slug = resolver.resolveSlug(request);
        if (slug.isPresent()) {
            try {
                Tenant tenant = tenants.findBySlug(slug.get())
                        .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                        .orElseThrow(() -> new UnknownTenantException(slug.get()));
                TenantContext.set(tenant.getId());
            } catch (UnknownTenantException e) {
                exceptionResolver.resolveException(request, response, null, e);
                return;
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
