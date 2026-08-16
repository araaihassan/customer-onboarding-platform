package co.ara.onboarding.authz;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The catalog, read-only, with each permission's allowedScopes.
 *
 * The role editor reads this rather than hardcoding scope options, so a permission
 * that is ALL-only shows a single option instead of a dropdown of four that would
 * be rejected on save. The catalog is identical for every tenant and reveals no
 * tenant data, but it still requires authentication — it describes the shape of the
 * authorization model, which is not something to hand to anonymous callers.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}/admin/permissions")
public class PermissionCatalogController {

    public record PermissionView(String key, String category, String resourceType,
                                 String description, List<String> allowedScopes) {}

    @GetMapping
    public List<PermissionView> list() {
        return PermissionCatalog.all().stream()
                .map(p -> new PermissionView(p.key(), p.category(), p.resourceType(),
                        p.description(),
                        p.allowedScopes().stream().map(Enum::name).sorted().toList()))
                .toList();
    }
}
