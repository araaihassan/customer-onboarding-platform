package co.ara.onboarding.identity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/t/{tenantSlug}/admin/users")
public class UserAdminController {

    private final UserAdminService users;

    public UserAdminController(UserAdminService users) { this.users = users; }

    @GetMapping
    public Page<UserAdminService.UserView> list(
            @RequestParam(required = false) String search, Pageable pageable) {
        return users.list(search, pageable);
    }

    @GetMapping("/{id}")
    public UserAdminService.UserView get(@PathVariable UUID id) { return users.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserAdminService.UserView create(@RequestBody UserAdminService.CreateUserRequest request) {
        return users.create(request);
    }

    @PutMapping("/{id}")
    public UserAdminService.UserView update(@PathVariable UUID id,
                                            @RequestBody UserAdminService.UpdateUserRequest request) {
        return users.update(id, request);
    }

    @PostMapping("/{id}/roles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignRole(@PathVariable UUID id, @RequestBody Map<String, UUID> body) {
        users.assignRole(id, body.get("roleId"));
    }

    /** No DELETE mapping anywhere: users are deactivated, never removed (spec 9.4). */
    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id) { users.deactivate(id); }
}
