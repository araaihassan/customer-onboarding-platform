package co.ara.onboarding.journey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

/**
 * No delete-by-case finder: case_attribute_value carries no GRANT DELETE (business
 * values are never hard-deleted), so CaseService.update upserts existing rows in
 * place instead of clearing and reinserting.
 *
 * findByCaseId is called directly from CaseEngine (Task 15). CaseEngine is listed
 * by fully-qualified name in
 * AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS (sub-project 3A Task 2 rebound
 * that rule to bind on repository injection rather than class name, so this is a
 * reviewable line in that list, not a *Service/*Directory naming dodge) -- because
 * every finder call inside CaseEngine, this one included, keys off a field of a
 * Case or Stage object its caller already resolved through AuthorizedQuery/lockById,
 * never a fresh caller-supplied id, so a direct child finder here widens visibility
 * by nothing.
 */
public interface CaseAttributeValueRepository
        extends JpaRepository<CaseAttributeValue, UUID>, JpaSpecificationExecutor<CaseAttributeValue> {

    List<CaseAttributeValue> findByCaseId(UUID caseId);
}
