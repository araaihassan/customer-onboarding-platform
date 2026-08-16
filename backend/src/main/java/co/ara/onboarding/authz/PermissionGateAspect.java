package co.ara.onboarding.authz;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Enforces @RequirePermission.
 *
 * The gate answers only "does the user hold this permission at ANY scope". Which
 * records they may touch is AuthorizationPredicateBuilder's job — the two are
 * separate on purpose, so a coarse yes/no can never be mistaken for record-level
 * authorization.
 */
@Aspect
@Component
@Order(300)   // explicit position in the advice chain — see enforce()
public class PermissionGateAspect {

    private final AuthorizationService authorization;

    public PermissionGateAspect(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    /**
     * This advice must run INSIDE the transaction and after the tenant is bound.
     * Resolving permissions queries user_role, role and role_grant, all
     * RLS-protected, so it returns rows only once TenantTransactionBinder has set
     * app.tenant_id on the transaction's connection. Running earlier would take a
     * pooled connection with no tenant GUC, RLS would return nothing, and the gate
     * would deny every request — a failure that reads as a permissions bug rather
     * than an ordering one.
     *
     * The chain is: transaction advisor (order 100, pinned in TransactionConfig) →
     * TenantTransactionBinder (200) → this (300).
     *
     * @Order(300) is explicitness, not a fix: removing it was tested and the suite
     * still passed, because an unordered aspect gets LOWEST_PRECEDENCE
     * (Integer.MAX_VALUE), which is already outside-most in number and therefore
     * innermost in execution. The annotation pins the position so a later aspect
     * cannot be introduced between the binder and this gate without someone
     * choosing a number and thinking about it.
     */
    @Before("@annotation(co.ara.onboarding.authz.RequirePermission)")
    public void enforce(JoinPoint joinPoint) {
        var signature = (MethodSignature) joinPoint.getSignature();
        var annotation = signature.getMethod().getAnnotation(RequirePermission.class);
        if (!authorization.has(annotation.value())) {
            // Deliberately no permission name in the message: do not teach a caller
            // which permission would unlock the endpoint.
            throw new AccessDeniedException("Forbidden");
        }
    }
}
