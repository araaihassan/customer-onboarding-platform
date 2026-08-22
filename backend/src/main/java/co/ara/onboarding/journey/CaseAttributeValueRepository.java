package co.ara.onboarding.journey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * No delete-by-case finder: case_attribute_value carries no GRANT DELETE (business
 * values are never hard-deleted), so CaseService.update upserts existing rows in
 * place instead of clearing and reinserting.
 */
public interface CaseAttributeValueRepository
        extends JpaRepository<CaseAttributeValue, UUID>, JpaSpecificationExecutor<CaseAttributeValue> {}
