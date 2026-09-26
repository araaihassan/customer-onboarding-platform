package co.ara.onboarding.agreement;

import co.ara.onboarding.customer.ContactStatus;
import co.ara.onboarding.identity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AgreementSignatoryRepository extends JpaRepository<AgreementSignatory, UUID>, JpaSpecificationExecutor<AgreementSignatory> {

    @Query("select s from AgreementSignatory s where s.agreementId = :agreementId order by s.sortOrder, s.id")
    List<AgreementSignatory> ofAgreement(@Param("agreementId") UUID agreementId);

    @Modifying
    @Query("delete from AgreementSignatory s where s.agreementId = :agreementId")
    void clearFor(@Param("agreementId") UUID agreementId);

    /**
     * Discovery-only, deliberately not a {@code findBy*} shape -- the {@code
     * AgreementRepository.liveFor}/{@code RequirementRepository.satisfiedBy}
     * precedent -- so {@code
     * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}'s
     * finder predicate never binds on it and it needs no exclusion there.
     * Fed only a {@code contactId} already frozen onto a signatory of an
     * agreement the caller has already resolved through {@link
     * co.ara.onboarding.authz.AuthorizedQuery} under {@code agreement.manage}
     * -- re-resolving this id through {@code AuthorizedQuery} under {@code
     * contact.view} would be wrong, not merely redundant, because a submitter
     * may legitimately lack {@code contact.view} entirely, and 404ing the
     * submit on that account would refuse a perfectly valid one.
     */
    @Query("select c.status from CustomerContact c where c.id = :contactId")
    ContactStatus contactStatusOf(@Param("contactId") UUID contactId);

    /** Same reasoning as {@link #contactStatusOf}, for an INTERNAL signatory's {@code userId} against {@code user.view}. */
    @Query("select u.status from AppUser u where u.id = :userId")
    UserStatus userStatusOf(@Param("userId") UUID userId);
}
