package co.ara.onboarding.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pins the transaction advisor's order so TenantTransactionBinder (@Order(200))
 * runs INSIDE the transaction it is binding the tenant to.
 */
@Configuration
@EnableTransactionManagement(order = 100)
public class TransactionConfig {
}
