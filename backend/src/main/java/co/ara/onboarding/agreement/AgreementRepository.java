package co.ara.onboarding.agreement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgreementRepository extends JpaRepository<Agreement, UUID>, JpaSpecificationExecutor<Agreement> {

    /**
     * Test-only convenience, the identical shape
     * {@code document.DocumentRequestRepository.findByCaseId} already has --
     * unused in main, real reads of a case's agreements go through
     * AuthorizedQuery/AgreementService once that exists.
     */
    List<Agreement> findByCaseId(UUID caseId);

    /**
     * Instantiation's existence check: the requirement's live agreement, if any.
     * Named {@code liveFor} rather than a {@code findBy*} shape deliberately, the
     * {@code journey.RequirementRepository.satisfiedBy} precedent -- so
     * {@code AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}'s
     * name-bound finder predicate does not bind on it. Fed only requirement ids
     * the caller controls (case-instantiation time, never a raw id from a URL or
     * request body).
     */
    @Query("select a from Agreement a where a.requirementId = :requirementId and a.status <> co.ara.onboarding.agreement.AgreementStatus.CANCELLED")
    Optional<Agreement> liveFor(@Param("requirementId") UUID requirementId);
}
