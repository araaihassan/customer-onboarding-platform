package co.ara.onboarding.document;

/**
 * Task 32: the `docs` screen's "08 VISIBLE · 61 HIDDEN BY SCOPE" line
 * (`SCREENS.md` §7). {@code visible} is the caller's own fully-authorized
 * {@link DocumentService#list} count under the same filter; {@code hidden} is
 * the codebase's second deliberate {@code AuthorizedQuery} bypass -- see
 * {@link DocumentService#visibilitySummary}'s own javadoc for the full safety
 * argument. Never negative -- {@link DocumentService#visibilitySummary}
 * clamps it before this record is ever constructed.
 */
public record DocumentVisibilitySummaryView(long visible, long hidden) {}
