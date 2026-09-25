package co.ara.onboarding.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerContactRepository
        extends JpaRepository<CustomerContact, UUID>, JpaSpecificationExecutor<CustomerContact> {

    List<CustomerContact> findByCustomerId(UUID customerId);

    /**
     * At most one contact links to a given app_user (the portal invitation flow
     * sets userId once, on acceptance) -- added for
     * scoping.CustomerPortalContactDirectory, which resolves a PORTAL actor's
     * contact record by their userId rather than a customerId.
     */
    Optional<CustomerContact> findByUserId(UUID userId);
}
