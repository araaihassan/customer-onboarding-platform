package co.ara.onboarding.authz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 6.3 amendment. The last signature calls journey.RequirementService.satisfy,
 * gated milestone.complete; a template holding agreement.sign_record without
 * milestone.complete at an equal-or-broader scope would have every final signature
 * refused and rolled back. Derived over RoleTemplates.all(), never a list of names.
 *
 * The breadth comparison is {@link Scope#atLeastAsBroad(Scope)}, added for this
 * test: neither {@code RoleService.refuseEscalation} nor
 * {@code RoleTemplateCoverageTest} already had a reusable one to call.
 * refuseEscalation's own inline check ({@code held.contains(Scope.ALL) ||
 * held.contains(grant.getScope())}) is deliberately NOT a total order -- its own
 * javadoc ("Comparison, not hierarchy") treats DEPARTMENT and TEAM as incomparable
 * sets, because delegation must not let an ALL-only holder hand out authority at a
 * scope they cannot themselves exercise-differently. This guard asks a different
 * question -- "does holding permission X at scope S imply holding permission Y at
 * scope S or broader" -- for which ALL > DEPARTMENT > TEAM > ASSIGNED is exactly
 * the right total order (a DEPARTMENT-scoped sign_record is satisfied by a
 * DEPARTMENT- or ALL-scoped milestone.complete, not by a TEAM-scoped one, since
 * TEAM would not cover every case DEPARTMENT does).
 */
class SignRecordImpliesMilestoneCompleteTest {

    @Test
    void everyTemplateHoldingSignRecordHoldsMilestoneCompleteAtLeastAsBroadly() {
        for (RoleTemplates.RoleTemplate template : RoleTemplates.all()) {
            Scope signRecord = template.grants().get(PermissionKeys.AGREEMENT_SIGN_RECORD);
            if (signRecord == null) continue;
            Scope complete = template.grants().get(PermissionKeys.MILESTONE_COMPLETE);
            assertThat(complete)
                    .as(template.name() + " holds agreement.sign_record at " + signRecord
                            + " but milestone.complete at " + complete)
                    .isNotNull();
            assertThat(complete.atLeastAsBroad(signRecord))
                    .as(template.name() + ": milestone.complete " + complete + " is narrower than sign_record " + signRecord)
                    .isTrue();
        }
    }

    @Test
    void theGuardIsNotVacuous() {
        assertThat(RoleTemplates.all())
                .anyMatch(t -> t.grants().containsKey(PermissionKeys.AGREEMENT_SIGN_RECORD));
    }
}
