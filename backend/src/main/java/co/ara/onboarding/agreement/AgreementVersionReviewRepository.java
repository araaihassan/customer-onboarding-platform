package co.ara.onboarding.agreement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgreementVersionReviewRepository extends JpaRepository<AgreementVersionReview, UUID>, JpaSpecificationExecutor<AgreementVersionReview> {

    @Query("select r from AgreementVersionReview r where r.agreementVersionId in :versionIds")
    List<AgreementVersionReview> ofVersions(@Param("versionIds") Collection<UUID> versionIds);
}
