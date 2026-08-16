package co.ara.onboarding.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface CustomerContactRepository
        extends JpaRepository<CustomerContact, UUID>, JpaSpecificationExecutor<CustomerContact> {

    List<CustomerContact> findByCustomerId(UUID customerId);
}
