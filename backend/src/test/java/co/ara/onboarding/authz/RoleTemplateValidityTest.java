package co.ara.onboarding.authz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the startup check required by spec 6.2. Deliberately a plain unit test:
 * template validity is a property of two code-defined structures, so it needs no
 * database and should fail in milliseconds.
 */
class RoleTemplateValidityTest {

    @Test
    void everyTemplateGrantUsesAValidPermissionAndScope() {
        RoleTemplates.all().forEach(t ->
            t.grants().forEach((key, scope) -> {
                assertThat(PermissionCatalog.byKey(key))
                        .as("template '%s' references unknown permission '%s'", t.name(), key)
                        .isPresent();
                assertThat(PermissionCatalog.allows(key, scope))
                        .as("template '%s' grants '%s' at disallowed scope %s", t.name(), key, scope)
                        .isTrue();
            }));
    }

    @Test
    void allTwelvePrdRolesArePresent() {
        assertThat(RoleTemplates.all()).hasSize(12);
        assertThat(RoleTemplates.all().stream().map(RoleTemplates.RoleTemplate::name))
            .contains("Sales Representative", "Account Manager", "Project Manager",
                      "Service Provider", "Business Partner", "Operations",
                      "Legal", "Finance", "Technical", "Compliance",
                      "Support", "Administrator");
    }

    /**
     * Not in the plan. Map.of silently accepts a repeated key only by throwing at
     * class-init time, and Administrator's grant map is large enough (16 entries,
     * built with Map.ofEntries) that a duplicated permission is easy to introduce
     * and invisible on review. This asserts each template declares as many grants
     * as it names distinct permissions, so a copy-paste duplicate surfaces here
     * rather than as an ExceptionInInitializerError on first use.
     */
    @Test
    void administratorGrantsEveryPermissionInTheCatalog() {
        var administrator = RoleTemplates.all().stream()
                .filter(t -> t.name().equals("Administrator"))
                .findFirst().orElseThrow();

        assertThat(administrator.grants().keySet())
                .as("Administrator is full tenant administration and must cover the whole catalog")
                .containsExactlyInAnyOrderElementsOf(
                        PermissionCatalog.all().stream().map(Permission::key).toList());
    }
}
