package co.ara.onboarding.journey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface CaseAttributeValueRepository
        extends JpaRepository<CaseAttributeValue, UUID>, JpaSpecificationExecutor<CaseAttributeValue> {}
