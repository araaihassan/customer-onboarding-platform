package co.ara.onboarding.authz;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DescriptorRegistry {

    private final Map<String, ResourceAuthorizationDescriptor<?>> byResourceType = new HashMap<>();
    private final Map<Class<?>, ResourceAuthorizationDescriptor<?>> byEntity = new HashMap<>();

    public DescriptorRegistry(List<ResourceAuthorizationDescriptor<?>> descriptors) {
        for (var d : descriptors) {
            byResourceType.put(d.resourceType(), d);
            byEntity.put(d.entityType(), d);
        }
    }

    /**
     * Fails startup when a permission allows a record-level scope but no descriptor
     * can resolve it. Refusing to boot is deliberate: the alternative is a scope
     * that silently resolves to nothing — or worse, to everything — at the first
     * request that exercises it (spec 6.9).
     *
     * Collects every problem before throwing rather than failing on the first, so
     * one restart tells you about all of them.
     */
    @PostConstruct
    public void validate() {
        List<String> problems = new ArrayList<>();
        for (Permission p : PermissionCatalog.all()) {
            boolean recordScoped = p.allowedScopes().stream().anyMatch(s -> s != Scope.ALL);
            if (!recordScoped) continue;
            if (p.resourceType() == null) {
                problems.add("permission '" + p.key() + "' allows record scopes but has no resourceType");
            } else if (!byResourceType.containsKey(p.resourceType())) {
                problems.add("permission '" + p.key() + "' targets resource type '"
                        + p.resourceType() + "' with no ResourceAuthorizationDescriptor");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Authorization configuration is incomplete: " + problems);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> ResourceAuthorizationDescriptor<T> forEntity(Class<T> entityType) {
        var d = byEntity.get(entityType);
        if (d == null) {
            throw new IllegalStateException(
                    "No ResourceAuthorizationDescriptor registered for " + entityType.getName());
        }
        return (ResourceAuthorizationDescriptor<T>) d;
    }

    public ResourceAuthorizationDescriptor<?> forResourceType(String resourceType) {
        var d = byResourceType.get(resourceType);
        if (d == null) {
            throw new IllegalStateException(
                    "No ResourceAuthorizationDescriptor registered for '" + resourceType + "'");
        }
        return d;
    }
}
