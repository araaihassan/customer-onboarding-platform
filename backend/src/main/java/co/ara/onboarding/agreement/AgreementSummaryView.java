package co.ara.onboarding.agreement;

/**
 * The lifecycle summary tile counts. {@code underReview} unions {@code
 * UNDER_REVIEW} and {@code APPROVED} -- an approved-but-not-yet-sent draft is
 * still "in review" from the tile's point of view. {@code signed} is
 * status SIGNED with {@code expiresAt} null or not yet passed -- it
 * deliberately excludes an EXPIRED-reading row (the display layer's own
 * EXPIRED chip is what surfaces that, not this summary), so a genuinely
 * expired agreement is counted nowhere in this record. {@code
 * expiringWithin30Days} is a NARROWER subset of {@code signed}, not a
 * disjoint bucket -- a row expiring in nine days is counted in both fields --
 * {@code status} SIGNED and {@code expiresAt} between {@code today} and
 * {@code today.plusDays(30)} inclusive on both ends.
 */
public record AgreementSummaryView(long draft, long underReview, long sent, long awaitingSignature,
        long signed, long expiringWithin30Days) {}
