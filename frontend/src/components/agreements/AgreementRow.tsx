"use client";

import { Button } from "@/components/ui/Button";
import { StatusPill } from "@/components/ui/StatusPill";
import { recordModeIncludesFile, type Agreement } from "@/lib/api/agreements";
import { formatDate } from "@/lib/format/date";
import { t } from "@/lib/i18n";
import { statusLabelKey, statusTone, toneRole } from "./statusChip";

const DAY_MS = 86_400_000;
const EXPIRY_WINDOW_DAYS = 30;

/** Kept under its original name for the agreements components; the formatter lives in `lib/format/date`. */
export const formatAgreementDate = formatDate;

/** Whole days from `now` (UTC midnight) to a date-only `expiresAt`; null unless a SIGNED agreement expires within 30 days. */
export function daysUntilExpiry(a: Agreement, now: Date): number | null {
  if (a.displayStatus !== "SIGNED" || !a.expiresAt) return null;
  const today = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  const days = Math.round((new Date(`${a.expiresAt}T00:00:00Z`).getTime() - today) / DAY_MS);
  return days >= 0 && days <= EXPIRY_WINDOW_DAYS ? days : null;
}

export function agreementMeta(a: Agreement): string {
  const parts: string[] = [];
  if (a.latestVersionNumber) parts.push(t("agreements.meta.version", { version: String(a.latestVersionNumber) }));
  if (a.recordMode) {
    parts.push(t(`agreement.recordMode.${a.recordMode}`).toLowerCase());
    if (!recordModeIncludesFile(a.recordMode)) parts.push(t("agreements.meta.noFile"));
  }
  if ((a.displayStatus === "SIGNED" || a.displayStatus === "EXPIRED") && a.signedAt) {
    parts.push(t("agreements.meta.signed", { date: formatAgreementDate(a.signedAt) }));
  }
  return parts.join(" · ");
}

/** One agreement row: `SCREENS.md` "Other tabs" shape -- 30x34 tile, name, mono meta line, status chip, Open. */
export function AgreementRow({ agreement, now, onOpen }: { agreement: Agreement; now: Date; onOpen: (id: string) => void }) {
  const status = agreement.displayStatus;
  const expiresIn = daysUntilExpiry(agreement, now);

  return (
    <div
      className="flex items-center bg-surface border border-line"
      style={{ gap: "var(--ob-space-11)", padding: "var(--ob-space-8) var(--ob-space-11)", borderRadius: "var(--ob-radius-9)" }}
    >
      <span
        aria-hidden
        className="inline-flex items-center justify-center"
        style={{
          width: 30,
          height: 34,
          borderRadius: "var(--ob-radius-5)",
          flexShrink: 0,
          background: "var(--ob-automation-bg)",
          color: "var(--ob-automation-fg)",
          font: "600 15px/1 var(--ob-font-family-ui)",
        }}
      >
        §
      </span>

      <div className="min-w-0 flex-1">
        <p className="truncate text-ink" style={{ font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}>
          {agreement.name || "—"}
        </p>
        <p className="truncate text-text-subtle" style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-data)" }}>
          <span data-testid="agreement-meta">{agreementMeta(agreement)}</span>
          {expiresIn !== null && (
            <span style={{ color: "var(--ob-warn-fg)" }}>
              {" · "}
              {t("agreements.meta.expiresIn", { days: String(expiresIn) })}
            </span>
          )}
        </p>
      </div>

      {status && <StatusPill status={t(statusLabelKey(status))} role={toneRole(statusTone(status))} />}

      <Button type="button" variant="secondary" disabled={!agreement.id} onClick={() => agreement.id && onOpen(agreement.id)}>
        {t("agreements.tab.open")}
      </Button>
    </div>
  );
}
