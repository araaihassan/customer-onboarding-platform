package co.ara.onboarding.journey;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseRepository extends JpaRepository<Case, UUID>, JpaSpecificationExecutor<Case> {

    /**
     * Re-reads a row AuthorizedQuery has already resolved, purely to take the row lock
     * that serialises reconciliation. It widens visibility by nothing -- the caller
     * already holds the resolved entity. It needs no exclusion from the finder rule at
     * all, class-wide or per-method: named lockById rather than findById, it never
     * matches the rule's own findAll|findOne|findById|findBy* target predicate in the
     * first place -- a reviewer reading a call site should see that a lock is being
     * taken. (Its only caller, CaseEngine, IS listed by fully-qualified name in
     * AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS, for its OTHER finder calls --
     * see CaseAttributeValueRepository/BranchRuleRepository's own javadocs.)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Case c where c.id = :id")
    Optional<Case> lockById(@Param("id") UUID id);

    List<Case> findByCustomerIdOrderByStartedAtDesc(UUID customerId);
    List<Case> findByVersionIdAndStatus(UUID versionId, CaseStatus status);
}
