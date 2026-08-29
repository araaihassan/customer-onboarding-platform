import Link from "next/link";
import { PlusIcon } from "@/components/icons";
import { shortId } from "@/lib/api/customers";
import type { Case, CaseStatus } from "@/lib/api/cases";
import { t } from "@/lib/i18n";

/**
 * The case switcher (uispecs README §5): one chip per case plus a dashed
 * "+ New case" chip, below the header's meta line. A real `<Link>`, not an
 * onClick that filters in place -- the case is the unit of work, so it
 * belongs in the URL, and a reload or a shared link must land on the same
 * one. Active chip = inverted fill, matching ui/Chip's own "active" shape
 * (duplicated here rather than reused because Chip renders a `<button>` and
 * this control must be a real link).
 */
export function CaseSwitcher({
  cases,
  activeCaseId,
  slug,
  customerId,
  canCreate,
  onCreateNew,
}: {
  cases: Case[];
  activeCaseId: string;
  slug: string;
  customerId: string;
  canCreate: boolean;
  onCreateNew: () => void;
}) {
  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      <span
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
        }}
      >
        {t("case.switcher.title")}
      </span>
      {/* Below 1024px, chips scroll horizontally in one line rather than
          wrapping to three -- review finding 11 named this screen as one of
          the fallback's two casualties. */}
      <div
        className="flex flex-nowrap items-center overflow-x-auto lg:flex-wrap lg:overflow-visible"
        style={{ gap: "var(--ob-space-8)" }}
      >
        {cases.map((c) => {
          const active = c.id === activeCaseId;
          return (
            <Link
              key={c.id}
              href={`/t/${slug}/customers/${customerId}/cases/${c.id}`}
              aria-current={active ? "page" : undefined}
              className="inline-flex items-center flex-shrink-0"
              style={{
                height: "var(--ob-control-height-sm)",
                borderRadius: "var(--ob-radius-5)",
                padding: "0 13px",
                gap: "5px",
                background: active ? "var(--ob-ink)" : "var(--ob-surface)",
                color: active ? "var(--ob-surface)" : "var(--ob-text-muted)",
                border: `1px solid var(${active ? "--ob-ink" : "--ob-line"})`,
                font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
              }}
            >
              <span
                aria-hidden
                style={{
                  width: 6,
                  height: 6,
                  borderRadius: "var(--ob-radius-full)",
                  background: dotColor(c.status),
                }}
              />
              {/* No fallback: name is @NotBlank and NOT NULL from creation (Q18) --
                  "not started" was never an honest stand-in for a missing name,
                  it was borrowed from the stage-name label this replaced. */}
              <span>{c.name}</span>
              <span
                style={{
                  font: "var(--ob-type-mono-chip-size)/var(--ob-type-mono-chip-line) var(--ob-font-family-data)",
                  letterSpacing: "var(--ob-type-mono-chip-tracking)",
                }}
              >
                {shortId(c.id)}
              </span>
            </Link>
          );
        })}

        {canCreate && (
          <button
            type="button"
            onClick={onCreateNew}
            className="inline-flex items-center flex-shrink-0"
            style={{
              height: "var(--ob-control-height-sm)",
              borderRadius: "var(--ob-radius-5)",
              padding: "0 13px",
              gap: "5px",
              border: "1px dashed var(--ob-line-strong)",
              color: "var(--ob-text-muted)",
              background: "transparent",
              font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
              cursor: "pointer",
            }}
          >
            <PlusIcon size={12} />
            {t("case.switcher.newCase")}
          </button>
        )}
      </div>
    </div>
  );
}

/**
 * Only three status-fg tokens map to a case status (ok/warn/risk, for
 * COMPLETED/ON_HOLD/CANCELLED), so ACTIVE borrows the accent instead of
 * doubling up ok-fg with COMPLETED -- otherwise an active and a completed
 * case would be indistinguishable by dot colour alone.
 */
function dotColor(status: CaseStatus | undefined): string {
  switch (status) {
    case "ACTIVE":
      return "var(--ob-accent-fg)";
    case "ON_HOLD":
      return "var(--ob-warn-fg)";
    case "COMPLETED":
      return "var(--ob-ok-fg)";
    case "CANCELLED":
      return "var(--ob-risk-fg)";
    default:
      return "var(--ob-accent-fg)";
  }
}
