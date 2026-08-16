package co.ara.onboarding.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission key a service method requires.
 *
 * Created in Task 7 as a marker so AuthorizationCoverageTest can reference it;
 * Task 13 adds PermissionGateAspect, which is what actually enforces it. Until
 * then this annotation carries intent but no behaviour — do not treat a method
 * annotated with it as gated before Task 13 lands.
 *
 * RUNTIME retention is required: the aspect reads it reflectively per call.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequirePermission {
    String value();
}
