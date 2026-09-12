package co.ara.onboarding.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission key(s) a service method requires.
 *
 * Created in Task 7 as a marker so AuthorizationCoverageTest can reference it;
 * Task 13 adds PermissionGateAspect, which is what actually enforces it. Until
 * then this annotation carries intent but no behaviour — do not treat a method
 * annotated with it as gated before Task 13 lands.
 *
 * RUNTIME retention is required: the aspect reads it reflectively per call.
 *
 * {@code value} is an array, not a single key — the gate passes if the actor
 * holds ANY one of them (OR, never AND), added in sub-project 3A's Task 20 fix
 * round for {@code PlanShapeService.currentApproval}: a method whose caller may
 * legitimately be someone who can only submit, only decide, or only view a
 * plan's shape needs to admit all three, not just one arbitrarily chosen key.
 * Every existing single-key usage (98 as of this change) keeps compiling
 * unchanged — Java's annotation array shorthand accepts a bare value in place
 * of a one-element array literal, so {@code @RequirePermission(KEY)} still
 * means exactly what it always did.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequirePermission {
    String[] value();
}
