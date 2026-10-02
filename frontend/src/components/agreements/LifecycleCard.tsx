import type { AgreementSummary } from "@/lib/api/agreements";
import { t } from "@/lib/i18n";

/**
 * The `agreements` lifecycle card (`SCREENS.md` §8): six counts, each a 22px/600 mono count in
 * its state colour, a 4px bar (`width = min(100, n x 1.9)%`) and a mono uppercase label.
 * Flat -- a bordered surface, no elevation. "Under review" is the backend's own union of
 * UNDER_REVIEW and APPROVED (`AgreementSummaryView`), not a separate bucket.
 */
const CELLS: ReadonlyArray<{
  key: string;
  labelKey: string;
  field: keyof AgreementSummary;
  color: string;
}> = [
  { key: "draft", labelKey: "agreements.lifecycle.draft", field: "draft", color: "var(--ob-text-2)" },
  { key: "underReview", labelKey: "agreements.lifecycle.underReview", field: "underReview", color: "var(--ob-info-fg)" },
  { key: "sent", labelKey: "agreements.lifecycle.sent", field: "sent", color: "var(--ob-warn-fg)" },
  {
    key: "awaitingSignature",
    labelKey: "agreements.lifecycle.awaitingSignature",
    field: "awaitingSignature",
    color: "var(--ob-warn-fg)",
  },
  { key: "signed", labelKey: "agreements.lifecycle.signed", field: "signed", color: "var(--ob-ok-fg)" },
  {
    key: "expiring",
    labelKey: "agreements.lifecycle.expiring",
    field: "expiringWithin30Days",
    color: "var(--ob-risk-fg)",
  },
];

/** Rounded so floating-point noise (6 x 1.9 = 11.399999999999999) never reaches the style. */
export function lifecycleBarWidth(n: number): string {
  return `${Math.min(100, Math.round(n * 1.9 * 100) / 100)}%`;
}

export function LifecycleCard({ summary }: { summary: AgreementSummary }) {
  return (
    <section
      aria-label={t("agreements.lifecycle.label")}
      className="bg-surface border border-line"
      style={{ borderRadius: "var(--ob-radius-11)", padding: "var(--ob-space-16)" }}
    >
      <p
        className="text-text-subtle"
        style={{
          font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
          letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
          marginBottom: "var(--ob-space-13)",
        }}
      >
        {t("agreements.list.eyebrow")}
      </p>
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(120px, 1fr))",
          gap: "var(--ob-space-16)",
        }}
      >
        {CELLS.map((cell) => {
          const n = summary[cell.field] ?? 0;
          return (
            <div key={cell.key} data-testid={`lifecycle-${cell.key}`} className="min-w-0">
              <p style={{ font: "600 22px/1.1 var(--ob-font-family-data)", color: cell.color }}>{n}</p>
              <div
                aria-hidden
                className="bg-line-faint"
                style={{ height: 4, borderRadius: 2, margin: "var(--ob-space-6) 0" }}
              >
                <div
                  data-testid={`lifecycle-bar-${cell.key}`}
                  style={{ height: "100%", borderRadius: 2, background: cell.color, width: lifecycleBarWidth(n) }}
                />
              </div>
              <p
                className="text-text-subtle"
                style={{
                  font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
                  letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
                  textTransform: "uppercase",
                }}
              >
                {t(cell.labelKey)}
              </p>
            </div>
          );
        })}
      </div>
    </section>
  );
}
