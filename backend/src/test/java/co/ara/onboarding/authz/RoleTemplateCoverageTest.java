package co.ara.onboarding.authz;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Derivable guard, not a typed list -- the lesson CLAUDE.md draws from all five
 * hand-written enumerations in sub-project 1 drifting behind the code. Sweeps
 * every permission catalogued at more than one scope and asserts at least one
 * non-Administrator template can exercise it, so a permission seeded to
 * Administrator only -- exercisable at no narrower scope by any seeded role --
 * surfaces here rather than staying invisible until someone reads the catalog
 * by hand. It will fail again the moment a later sub-project adds a permission
 * at several scopes without seeding it anywhere else.
 */
class RoleTemplateCoverageTest {

    /**
     * Reviewed exceptions, same shape as {@code RlsCoverageTest}'s allowlist:
     * adding an entry here is a deliberate act, not a way to make this test pass.
     *
     * Both were found by this guard on its first run -- it also flagged
     * {@code task.manage} and {@code approval.decide}, which this task fixes --
     * but neither is this task's scope (its brief names only those two), and
     * fixing either without a role review risks colliding with a known, only
     * partly-closed gap: {@code user.manage} at TEAM is still unusable by any
     * holder (CLAUDE.md's "TEAM-scoped user creation" item -- CreateUserRequest
     * has no teamIds field), so granting USER_MANAGE at TEAM to a template today
     * would seed a role that holds the permission but 404s on every create.
     * customer.deactivate has no documented scope decision anywhere in QA.md or
     * the PRD. Recorded in CLAUDE.md as a new finding; left for a future task.
     */
    private static final Set<String> ADMINISTRATOR_ONLY_PENDING_REVIEW = Set.of(
            "user.manage", "customer.deactivate"
    );

    @Test
    void everyRecordScopedPermissionIsHeldByAtLeastOneNonAdministratorTemplate() {
        List<String> administratorOnly = PermissionCatalog.all().stream()
                .filter(p -> p.allowedScopes().size() > 1)          // catalogued at more than ALL
                .map(Permission::key)
                .filter(key -> !ADMINISTRATOR_ONLY_PENDING_REVIEW.contains(key))
                .filter(key -> RoleTemplates.all().stream()
                        .filter(t -> !t.name().equals("Administrator"))
                        .noneMatch(t -> t.grants().containsKey(key)))
                .toList();

        assertThat(administratorOnly)
                .as("a permission catalogued at several scopes but granted only to "
                  + "Administrator cannot be exercised at any narrower scope by any seeded role")
                .isEmpty();
    }

    /**
     * The generic sweep above only proves "held by at least one non-Administrator
     * template" -- it says nothing about WHICH one. Q9's own department list
     * names Legal, Finance and Compliance specifically for document review, so
     * that specific assignment needs its own assertion; a role seeding that
     * satisfied the generic test by granting document.review to, say, Support
     * alone would pass it while missing the actual product requirement.
     */
    @Test
    void documentReviewIsHeldByLegalFinanceAndCompliance() {
        Set<String> holders = RoleTemplates.all().stream()
                .filter(t -> t.grants().containsKey(PermissionKeys.DOCUMENT_REVIEW))
                .map(RoleTemplates.RoleTemplate::name)
                .collect(Collectors.toSet());

        assertThat(holders)
                .as("document.review must be held by Legal, Finance and Compliance, "
                  + "the three role names Q9's own department list names")
                .contains("Legal", "Finance", "Compliance");
    }
}
