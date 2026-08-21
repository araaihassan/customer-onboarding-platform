package co.ara.onboarding.workflow;

import java.util.Set;

/**
 * The customer fields a condition may name when its source is CUSTOMER -- the three
 * {@code journey.CustomerFacts} exposes. Named as bare strings here, deliberately: a
 * real dependency on {@code journey} (or {@code customer}) would pull a domain module
 * into this one for three literals, and {@code workflow} authors and validates
 * conditions without ever needing to know how a fact is computed. Task 10 adds the
 * test that keeps this list and {@code journey.CustomerFacts} in agreement, because a
 * key present in one and not the other is a condition that can never be true.
 */
final class CustomerFactKeys {

    private CustomerFactKeys() {}

    static final Set<String> ALL = Set.of("status", "industry", "country");
}
