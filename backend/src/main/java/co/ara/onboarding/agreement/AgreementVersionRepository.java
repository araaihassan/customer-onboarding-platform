package co.ara.onboarding.agreement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AgreementVersionRepository extends JpaRepository<AgreementVersion, UUID>, JpaSpecificationExecutor<AgreementVersion> {

    @Query("select coalesce(max(v.versionNumber), 0) from AgreementVersion v where v.agreementId = :agreementId")
    int maxVersionNumber(@Param("agreementId") UUID agreementId);

    @Query("select v from AgreementVersion v where v.agreementId = :agreementId order by v.versionNumber desc")
    List<AgreementVersion> ofAgreementNewestFirst(@Param("agreementId") UUID agreementId);
}
