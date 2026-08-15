package co.ara.onboarding.authz;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tenant-owned roles. Every public method carries @RequirePermission, which
 * AuthorizationCoverageTest enforces structurally. Note that the annotation is
 * declarative only until Task 13's PermissionGateAspect — these methods are not
 * actually gated yet.
 */
@Service
public class RoleService {

    /**
     * A role and everything it grants, in one read.
     *
     * The grants are on the view rather than behind a second call because a role
     * without them is just a name: every caller that loads a role does so to see or
     * change what it grants, which is exactly why Role maps them EAGER.
     */
    public record RoleView(UUID id, String name, String description, boolean enabled,
                           boolean systemTemplate, Map<String, Scope> grants) {}

    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final ActorDirectory actors;
    private final AuditRecorder audit;

    public RoleService(RoleRepository roles, UserRoleRepository userRoles,
                       ActorDirectory actors, AuditRecorder audit) {
        this.roles = roles;
        this.userRoles = userRoles;
        this.actors = actors;
        this.audit = audit;
    }

    /**
     * Every role in the tenant, with its grants.
     *
     * Not through AuthorizedQuery: role.view is ALL-only in the catalog, so there is
     * no narrower scope for a predicate to express, and Role has no
     * ResourceAuthorizationDescriptor for one to resolve against. Isolation here is
     * the tenant filter and RLS, the same two constraints every other role read
     * relies on.
     *
     * Sorted by name so the administration screen has a stable order; findAll over
     * a UUIDv7 id would order by creation time, which puts the twelve seeded
     * templates in RoleTemplates' declaration order and any later role at the end.
     */
    @RequirePermission(PermissionKeys.ROLE_VIEW)
    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roles.findAll().stream()
                .map(RoleService::toView)
                .sorted(Comparator.comparing(RoleView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @RequirePermission(PermissionKeys.ROLE_MANAGE)
    @Transactional
    public UUID createRole(String name, String description, Map<String, Scope> grants) {
        Role role = new Role();
        role.setId(Uuid7.generate());
        role.setTenantId(TenantContext.getRequired());
        role.setName(name);
        role.setDescription(description == null ? "" : description);
        role.setEnabled(true);
        applyGrants(role, grants);
        roles.save(role);
        audit.record(AuditActions.ROLE_CREATED, "role", role.getId(),
                "Created role " + name, Map.of("grants", grants));
        return role.getId();
    }

    @RequirePermission(PermissionKeys.ROLE_MANAGE)
    @Transactional
    public void updateGrants(UUID roleId, Map<String, Scope> grants) {
        Role role = roles.findById(roleId).orElseThrow();

        // The flush between clear() and applyGrants() is required, not defensive.
        // role_grant carries UNIQUE (role_id, permission_key), and Hibernate's
        // action queue executes entity inserts BEFORE entity deletes within a
        // single flush. Re-granting a permission the role already holds at another
        // scope -- the ordinary way a role is edited -- therefore inserts the
        // replacement row while the old one is still present and violates the
        // constraint. Flushing here executes the orphan deletes first.
        //
        // Observed, not theorized: without this,
        // updateGrantsCanRescopeAnExistingPermission fails with
        // "duplicate key value violates unique constraint
        // role_grant_role_id_permission_key_key" on the insert.
        role.getGrants().clear();
        roles.saveAndFlush(role);

        applyGrants(role, grants);
        roles.save(role);
        audit.record(AuditActions.ROLE_UPDATED, "role", roleId,
                "Updated grants for role " + role.getName(), Map.of("grants", grants));
    }

    @RequirePermission(PermissionKeys.ROLE_MANAGE)
    @Transactional
    public void setEnabled(UUID roleId, boolean enabled) {
        Role role = roles.findById(roleId).orElseThrow();
        role.setEnabled(enabled);
        roles.save(role);
        if (!enabled) {
            audit.record(AuditActions.ROLE_DISABLED, "role", roleId,
                    "Disabled role " + role.getName(), Map.of());
        }
    }

    @RequirePermission(PermissionKeys.ROLE_MANAGE)
    @Transactional
    public void deleteRole(UUID roleId) {
        if (userRoles.countByRoleId(roleId) > 0) {
            throw new IllegalStateException("Role still has users assigned; disable it instead");
        }
        roles.deleteById(roleId);
    }

    /**
     * Refuses PORTAL users outright. Internal roles carry internal permissions, and a
     * customer contact holding one would see staff surfaces — the userType boundary
     * is the whole separation between the application and the portal (spec 5.2), and
     * nothing else enforces it at assignment time.
     *
     * The check goes through ActorDirectory rather than AppUserRepository because
     * authz must not depend on identity; identity depends on authz.
     */
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public void assignRole(UUID userId, UUID roleId) {
        var actor = actors.findActor(userId)
                .orElseThrow(() -> new NoSuchElementException("No such user"));
        if (actor.userType() == UserType.PORTAL) {
            throw new InvalidGrantException(
                    "Portal users cannot hold internal roles");
        }
        userRoles.save(new UserRole(TenantContext.getRequired(), userId, roleId));
        audit.record(AuditActions.USER_ROLE_ASSIGNED, "app_user", userId,
                "Assigned role", Map.of("roleId", roleId.toString()));
    }

    /**
     * Removing an assignment is what makes a role deletable — deleteRole refuses
     * while anyone still holds it. Idempotent: unassigning a role nobody holds is
     * not an error.
     */
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public void unassignRole(UUID userId, UUID roleId) {
        userRoles.findById(new UserRoleId(userId, roleId)).ifPresent(userRoles::delete);
    }

    private static RoleView toView(Role role) {
        return new RoleView(role.getId(), role.getName(), role.getDescription(),
                role.isEnabled(), role.isSystemTemplate(),
                role.getGrants().stream().collect(Collectors.toMap(
                        RoleGrant::getPermissionKey, RoleGrant::getScope)));
    }

    /**
     * Validates every grant against the catalog before persisting any of them, so
     * a partially-valid update never leaves a partially-applied role. Both loops
     * are needed: the first is a complete validation pass, the second mutates.
     */
    private void applyGrants(Role role, Map<String, Scope> grants) {
        grants.forEach((key, scope) -> {
            if (PermissionCatalog.byKey(key).isEmpty()) throw new InvalidGrantException(key);
            if (!PermissionCatalog.allows(key, scope)) throw new InvalidGrantException(key, scope);
        });
        grants.forEach((key, scope) -> {
            RoleGrant g = new RoleGrant();
            g.setId(Uuid7.generate());
            g.setTenantId(role.getTenantId());
            g.setRole(role);
            g.setPermissionKey(key);
            g.setScope(scope);
            role.getGrants().add(g);
        });
    }
}
