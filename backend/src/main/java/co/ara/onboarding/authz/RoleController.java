package co.ara.onboarding.authz;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Roles are authorization metadata rather than business records, which is why this
 * is the one controller in the system exposing DELETE. RoleService refuses it while
 * any user still holds the role, so deletion is only ever available for a role that
 * has no effect on anybody.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}/admin/roles")
public class RoleController {

    public record RoleRequest(String name, String description, Map<String, Scope> grants) {}

    private final RoleService roles;

    public RoleController(RoleService roles) { this.roles = roles; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> create(@RequestBody RoleRequest request) {
        return Map.of("id", roles.createRole(request.name(), request.description(), request.grants()));
    }

    @PutMapping("/{id}/grants")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateGrants(@PathVariable UUID id, @RequestBody Map<String, Scope> grants) {
        roles.updateGrants(id, grants);
    }

    @PostMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable UUID id) { roles.setEnabled(id, false); }

    @PostMapping("/{id}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(@PathVariable UUID id) { roles.setEnabled(id, true); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { roles.deleteRole(id); }
}
