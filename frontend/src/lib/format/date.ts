import { t } from "@/lib/i18n";

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

/**
 * Age of a timestamp as mono-uppercase copy ("12 MIN AGO"), falling back to `formatDate` from a
 * week on. A timestamp in the future (clock skew) reads as "just now". `now` is injectable for tests.
 */
export function formatRelative(iso: string, now: Date = new Date()): string {
  const elapsed = now.getTime() - new Date(iso).getTime();
  const minutes = Math.floor(elapsed / 60_000);
  if (minutes < 1) return t("time.justNow");
  if (minutes < 60) return t("time.minutesAgo", { n: String(minutes) });
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return t("time.hoursAgo", { n: String(hours) });
  const days = Math.floor(hours / 24);
  if (days < 2) return t("time.yesterday");
  if (days < 7) return t("time.daysAgo", { n: String(days) });
  return formatDate(iso);
}
