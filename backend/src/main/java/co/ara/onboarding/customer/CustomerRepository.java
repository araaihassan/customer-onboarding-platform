package co.ara.onboarding.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * JpaSpecificationExecutor is required, not incidental: Task 13's
 * AuthorizationPredicateBuilder turns a grant's scope into a Specification, and
 * Task 14's authorized queries compose it with the caller's filters. A finder
 * method that bypasses Specifications also bypasses scope.
 */
public interface CustomerRepository
        extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {}
