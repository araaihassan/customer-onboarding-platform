package co.ara.onboarding.journey;

import java.util.Optional;
import java.util.UUID;

/**
 * The facts journey needs about a customer -- and nothing else.
 *
 * A port, declared by the consumer and implemented by the provider, which is the
 * idiom authz.ActorDirectory and identity.UserSessionRevoker already establish. The
 * dependency therefore runs customer -> journey, and journey holds no Customer, no
 * CustomerRepository and no CustomerStatus. ModuleBoundaryTest enforces it.
 *
 * Deliberately no display data: no legal name, no contacts. The journey workspace
 * header composes this with the useCustomer(customerId) call the customers screen
 * already ships, which costs one client request and keeps the port from growing into
 * a second customer API.
 */
public interface CustomerDirectory {
    Optional<CustomerFacts> findVisible(UUID customerId);
}
