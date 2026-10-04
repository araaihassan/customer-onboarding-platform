const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** `d MMM yyyy` from a date-only (`2026-08-14`) or ISO timestamp string, read in UTC so the day never shifts. */
export function formatDate(value: string): string {
  const d = new Date(value.length === 10 ? `${value}T00:00:00Z` : value);
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
}

/**
 * `d MMM` (no year) from a date-only or ISO timestamp, read in UTC: the value is a calendar date
 * (or a UTC instant shown as its UTC day), so it must not shift with the viewer's zone. Empty string
 * for a missing or unparseable value. A fixed month table, not Intl: ICU 72+ renders September as
 * "Sept" in en-GB, and the design wants "21 Sep".
 */
export function formatDayMonth(value: string | undefined): string {
  if (!value) return "";
  const d = new Date(value.length === 10 ? `${value}T00:00:00Z` : value);
  return Number.isNaN(d.getTime()) ? "" : `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
}
