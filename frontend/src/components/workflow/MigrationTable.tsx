import Link from "next/link";
import type { ReactNode } from "react";
import { EmptyState } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import { shortId } from "@/lib/api/customers";
import type { Candidate } from "@/lib/api/workflows";
import { t } from "@/lib/i18n";

/**
 * The migration review table (Task 25, uispecs README §6's "Review migration"
 * button target -- the screen itself is not in the design system, so it is
 * composed from existing primitives rather than new ones).
 *
 * Every ineligible row carries the reason MigrationService computed: "18
 * eligible" without saying why the other 13 are not is a number an admin
 * cannot act on, and it is also the number they will ask about first.
 *
 * Ineligible rows cannot be selected, and select-all only ever selects
 * eligible ones, because MigrateService.migrate refuses an ineligible case
 * rather than silently skipping it -- this UI must never build a request it
 * already knows will fail.
 */
export function MigrationTable({
  candidates,
  slug,
  selected,
  onToggle,
  onSelectAll,
}: {
  candidates: Candidate[];
  slug: string;
  selected: Set<string>;
  onToggle: (caseId: string) => void;
  onSelectAll: () => void;
}) {
  // Empty only when there is nothing on an older version at all -- an
  // ineligible-only list still has to render: its reason column is the whole
  // point of this table (this doc comment's own second paragraph), and an
  // admin cannot act on "0 eligible" without knowing why.
  if (candidates.length === 0) {
    return <EmptyState title={t("workflow.migration.empty.title")} description={t("workflow.migration.empty.description")} />;
  }

  const eligible = candidates.filter((c) => c.eligible);
  const allEligibleSelected = eligible.length > 0 && eligible.every((c) => c.caseId && selected.has(c.caseId));

  return (
    <table className="w-full" style={{ borderCollapse: "collapse" }}>
      <thead>
        <tr className="bg-bg-surface-subtle">
          <ColumnHeader>
            <input
              type="checkbox"
              aria-label={t("workflow.migration.selectAll")}
              checked={allEligibleSelected}
              onChange={onSelectAll}
            />
          </ColumnHeader>
          <ColumnHeader>{t("workflow.migration.table.case")}</ColumnHeader>
          <ColumnHeader>{t("workflow.migration.table.customer")}</ColumnHeader>
          <ColumnHeader>{t("workflow.migration.table.currentStage")}</ColumnHeader>
          <ColumnHeader>{t("workflow.migration.table.eligibility")}</ColumnHeader>
          <ColumnHeader>{t("workflow.migration.table.reason")}</ColumnHeader>
        </tr>
      </thead>
      <tbody>
        {candidates.map((candidate) => (
          <tr key={candidate.caseId} className="border-t border-border-subtle">
            <Cell>
              <input
                type="checkbox"
                aria-label={t("workflow.migration.selectCase", { id: candidate.caseId ?? "" })}
                checked={Boolean(candidate.caseId && selected.has(candidate.caseId))}
                disabled={!candidate.eligible}
                onChange={() => candidate.caseId && onToggle(candidate.caseId)}
              />
            </Cell>
            <Cell>
              <span className="text-text-secondary" style={MONO_TEXT}>
                {shortId(candidate.caseId)}
              </span>
            </Cell>
            <Cell>
              <Link href={`/t/${slug}/customers/${candidate.customerId}`} className="text-text-primary hover:underline">
                <span style={MONO_TEXT}>{shortId(candidate.customerId)}</span>
              </Link>
            </Cell>
            <Cell>
              <span className="text-text-secondary" style={BODY_TEXT}>
                {candidate.currentStageName || EMPTY}
              </span>
            </Cell>
            <Cell>
              <StatusPill
                status={candidate.eligible ? t("workflow.migration.eligible") : t("workflow.migration.notEligible")}
                role={candidate.eligible ? "on-track" : "blocked"}
              />
            </Cell>
            <Cell>
              <span className="text-text-muted" style={BODY_TEXT}>
                {candidate.reason || EMPTY}
              </span>
            </Cell>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const EMPTY = "—";

const BODY_TEXT = {
  font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
} as const;

const MONO_TEXT = {
  font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)",
} as const;

function ColumnHeader({ children }: { children: ReactNode }) {
  return (
    <th
      scope="col"
      className="text-left text-text-faint"
      style={{
        padding: "var(--ob-space-8) var(--ob-table-row-padding-x)",
        textTransform: "uppercase",
        letterSpacing: "0.08em",
        font: "var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
      }}
    >
      {children}
    </th>
  );
}

function Cell({ children }: { children: ReactNode }) {
  return (
    <td className="align-middle" style={{ padding: "var(--ob-table-row-padding-y) var(--ob-table-row-padding-x)" }}>
      {children}
    </td>
  );
}
