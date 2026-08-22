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
 * findByCaseId is called directly from CaseEngine (Task 15), which is exempt from
 * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly -- that rule
 * binds classes named *Service/*Directory, not *Engine -- because CaseEngine already
 * re-reads its case under a lock the caller's own AuthorizedQuery resolved first, so
 * a direct child finder here widens visibility by nothing.
 */
public interface CaseAttributeValueRepository
        extends JpaRepository<CaseAttributeValue, UUID>, JpaSpecificationExecutor<CaseAttributeValue> {

    List<CaseAttributeValue> findByCaseId(UUID caseId);
}
