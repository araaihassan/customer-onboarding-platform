package co.ara.onboarding.journey;

/** How many of the requested cases were actually migrated -- always every one asked for, since migrate() refuses rather than partially applies. */
public record MigrateResultView(int migrated) {}
