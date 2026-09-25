package co.ara.onboarding.agreement;

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
}
