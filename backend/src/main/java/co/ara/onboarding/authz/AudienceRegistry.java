package co.ara.onboarding.authz;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collects AudienceFilter beans by entity type. Unlike DescriptorRegistry this
 * has no validate() and no startup failure: an audience is opt-in, so a type
 * with no filter is the normal case, not a misconfiguration.
 *
 * forEntity returns Optional rather than throwing. That difference from
 * DescriptorRegistry.forEntity is the entire reason this is a separate registry
 * -- see AudienceFilter's own javadoc.
 */
@Component
public class AudienceRegistry {

    private final Map<Class<?>, AudienceFilter<?>> byEntity = new HashMap<>();

    public AudienceRegistry(List<AudienceFilter<?>> filters) {
        for (var f : filters) {
            AudienceFilter<?> previous = byEntity.put(f.entityType(), f);
            if (previous != null) {
                // Two filters for one entity would silently mean "last bean wins",
                // and which one wins would depend on classpath order.
                throw new IllegalStateException(
                        "Two AudienceFilters registered for " + f.entityType().getName()
                                + ": " + previous.getClass().getName()
                                + " and " + f.getClass().getName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<AudienceFilter<T>> forEntity(Class<T> entityType) {
        return Optional.ofNullable((AudienceFilter<T>) byEntity.get(entityType));
    }
}
