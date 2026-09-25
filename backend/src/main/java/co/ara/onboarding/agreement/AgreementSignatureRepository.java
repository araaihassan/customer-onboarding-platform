package co.ara.onboarding.agreement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AgreementSignatureRepository extends JpaRepository<AgreementSignature, UUID>, JpaSpecificationExecutor<AgreementSignature> {

    @Query("select s from AgreementSignature s where s.agreementId = :agreementId order by s.recordedAt")
    List<AgreementSignature> ofAgreement(@Param("agreementId") UUID agreementId);
}
