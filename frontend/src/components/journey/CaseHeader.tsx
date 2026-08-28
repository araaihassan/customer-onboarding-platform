import { Avatar } from "@/components/ui/Avatar";
import { StatusPill } from "@/components/ui/StatusPill";
import { shortId } from "@/lib/api/customers";
import type { Case } from "@/lib/api/cases";
import type { Customer } from "@/lib/api/customers";
import { t } from "@/lib/i18n";

/**
 * The journey workspace header card (uispecs README §5). `CaseView` carries
 * customerId and no display data by design (spec 3.2) -- the workspace
 * composes it with the customer already loaded by `useCustomer`, one extra
 * client call rather than journey importing customer.
 *
 * The right-aligned percentage and bar are the case's own progress, kept
 * separate from the five fact columns below rather than folded in as a
 * sixth -- the design draws it larger and on its own, and it is the one
 * number every reader wants first.
 */
export function CaseHeader({ caseData, customer }: { caseData: Case; customer: Customer }) {
  return (
    <div
      className="bg-surface border border-line"
      style={{ borderRadius: "var(--ob-card-radius)", padding: "var(--ob-space-20)" }}
    >
      <div className="flex items-start" style={{ gap: "var(--ob-space-16)" }}>
        <Avatar name={customer.displayName ?? ""} kind="company" size={46} />

        <div className="flex-1 min-w-0">
          <div className="flex items-center flex-wrap" style={{ gap: "var(--ob-space-11)" }}>
            <h1
              className="text-ink min-w-0 truncate"
              style={{ font: "600 var(--ob-type-section-heading-size)/var(--ob-type-section-heading-line) var(--ob-font-family-ui)" }}
            >
              {customer.displayName}
            </h1>
            <StatusPill status={caseData.status} />
          </div>

          <p
            className="text-text-faint"
            style={{ font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)", marginTop: 4 }}
          >
            {[shortId(caseData.id), t("case.header.version", { version: `v${caseData.versionNo ?? "?"} (frozen)` })]
              .filter(Boolean)
              .join(" · ")}
          </p>

          <div
            data-testid="case-fact-grid"
            className="grid grid-cols-3 xl:grid-cols-5"
            style={{ gap: "var(--ob-space-20)", marginTop: "var(--ob-space-16)" }}
          >
            <Fact label={t("case.header.stage")} value={caseData.currentStageName || t("case.header.noStage")} />
            <Fact label={t("case.header.started")} value={toDateOnly(caseData.startedAt)} mono />
            <Fact label={t("case.header.targetCompletion")} value={toDateOnly(caseData.targetCompletionDate)} mono />
            <Fact label={t("case.header.holdDays")} value={String(caseData.totalHoldDays ?? 0)} mono />
            <Fact label={t("case.header.status")} value={caseData.status ? t(`case.status.${caseData.status}`) : "—"} />
          </div>
        </div>

        <div className="flex-shrink-0 flex flex-col items-end" style={{ gap: 6 }}>
          <span
            className="text-ink"
            style={{ font: "600 var(--ob-type-hero-metric-size)/var(--ob-type-hero-metric-line) var(--ob-font-family-data)" }}
          >
            {caseData.progressPercent ?? 0}%
          </span>
          <div
            className="bg-line-faint overflow-hidden"
            style={{ width: 150, height: 7, borderRadius: "var(--ob-radius-4)" }}
          >
            <div
              className="h-full bg-ok-fg"
              style={{ width: `${caseData.progressPercent ?? 0}%`, background: "var(--ob-ok-fg)" }}
            />
          </div>
        </div>
      </div>
    </div>
  );
}

/** A trailing "Z"/offset already means the ISO prefix is the calendar date everywhere -- slicing avoids a Date() timezone shift landing on the wrong day. */
function toDateOnly(iso: string | undefined): string {
  return iso ? iso.slice(0, 10) : "—";
}

function Fact({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <p
        className="text-text-faint"
        style={{
          textTransform: "uppercase",
          letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
          font: "var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
        }}
      >
        {label}
      </p>
      <p
        className="text-ink truncate"
        style={{
          marginTop: "var(--ob-space-4)",
          font: mono
            ? "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-data)"
            : "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
        }}
      >
        {value}
      </p>
    </div>
  );
}
