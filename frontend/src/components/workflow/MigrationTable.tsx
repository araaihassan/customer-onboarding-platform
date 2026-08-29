import Link from "next/link";
import { CheckIcon } from "@/components/icons";
import { DataTable } from "@/components/ui/DataTable";
import { EmptyState } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import { shortId } from "@/lib/api/customers";
import type { Candidate } from "@/lib/api/workflows";
import { t } from "@/lib/i18n";

/**
 * The migration review table (Task 25, uispecs README §6's "Review migration"
 * button target -- the screen itself is not in the design system, so it is
 * composed from existing primitives rather than new ones). Task 34 converts it
 * to compose the shared `DataTable` primitive (Task 22, restyled by
 * `CustomerTable` in Task 27), with `framed={false}` -- the migration page
 * already wraps this component in its own frame, and `DataTable`'s own doc
 * comment names this exact caller as the reason that prop exists.
 *
 * Every ineligible row carries the reason MigrationService computed: "18
 * eligible" without saying why the other 13 are not is a number an admin
 * cannot act on, and it is also the number they will ask about first.
 *
 * Ineligible rows cannot be selected, and select-all only ever selects
 * eligible ones, because MigrateService.migrate refuses an ineligible case
 * rather than silently skipping it -- this UI must never build a request it
 * already knows will fail.
 *
 * `DataTable`'s header cells only ever render a plain string label (there is
 * no header-renderer seam, by design -- every other caller's header is text),
 * so the interactive "select all eligible" control cannot live inside the
 * grid's own generated header row the way the old hand-rolled `<table>`'s
 * `<th>` let it. It is rendered as its own small control just above the
 * table instead, keeping the exact same aria-label, checked/onChange wiring
 * and eligible-only semantics -- only its position moved.
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

  const columns = [
    {
      key: "select",
      label: t("workflow.migration.table.select"),
      width: "34px",
      render: (candidate: Candidate) => (
        <RowCheckbox
          checked={Boolean(candidate.caseId && selected.has(candidate.caseId))}
          disabled={!candidate.eligible}
          ariaLabel={t("workflow.migration.selectCase", { id: candidate.caseId ?? "" })}
          onChange={() => candidate.caseId && onToggle(candidate.caseId)}
        />
      ),
    },
    {
      key: "case",
      label: t("workflow.migration.table.case"),
      width: "1fr",
      render: (candidate: Candidate) => (
        <span className="text-text-2" style={MONO_TEXT}>
          {shortId(candidate.caseId)}
        </span>
      ),
    },
    {
      key: "customer",
      label: t("workflow.migration.table.customer"),
      width: "1fr",
      render: (candidate: Candidate) => (
        <Link href={`/t/${slug}/customers/${candidate.customerId}`} className="text-ink hover:underline">
          <span style={MONO_TEXT}>{shortId(candidate.customerId)}</span>
        </Link>
      ),
    },
    {
      key: "currentStage",
      label: t("workflow.migration.table.currentStage"),
      width: "1.1fr",
      render: (candidate: Candidate) => (
        <span className="text-text-2" style={BODY_TEXT}>
          {candidate.currentStageName || EMPTY}
        </span>
      ),
    },
    {
      key: "eligibility",
      label: t("workflow.migration.table.eligibility"),
      width: "1fr",
      render: (candidate: Candidate) => (
        <StatusPill
          status={candidate.eligible ? t("workflow.migration.eligible") : t("workflow.migration.notEligible")}
          role={candidate.eligible ? "ok" : "risk"}
        />
      ),
    },
    {
      key: "reason",
      label: t("workflow.migration.table.reason"),
      width: "1.8fr",
      render: (candidate: Candidate) => (
        <span className="text-text-muted" style={BODY_TEXT}>
          {candidate.reason || EMPTY}
        </span>
      ),
    },
  ];

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
      <label className="inline-flex items-center cursor-pointer" style={{ gap: "var(--ob-space-8)" }}>
        <RowCheckbox checked={allEligibleSelected} ariaLabel={t("workflow.migration.selectAll")} onChange={onSelectAll} />
        <span className="text-text-muted" style={BODY_TEXT}>
          {t("workflow.migration.selectAll")}
        </span>
      </label>

      <DataTable
        columns={columns}
        rows={candidates}
        getRowKey={(candidate) => candidate.caseId ?? ""}
        framed={false}
        stackedColumn={(candidate) => (
          <MigrationCard
            candidate={candidate}
            slug={slug}
            selected={selected}
            onToggle={onToggle}
          />
        )}
      />
    </div>
  );
}

/** An em dash, so a missing optional value reads as absent rather than broken. */
const EMPTY = "—";

/** Table cell (`DESIGN_TOKENS.md` Typography: 12.5px/400). */
const BODY_TEXT = {
  font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;

/** Mono data (11px) -- case and customer ids. */
const MONO_TEXT = {
  font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
} as const;

/**
 * A checkbox matching Task 17's restyled `Checkbox` (17px, radius-5, `ok-fg`
 * fill, `canvas`-coloured check mark) but exposing an `ariaLabel` rather than
 * forcing a visible text label next to the box -- the shared `Checkbox`
 * component always renders one, which would duplicate the case id already
 * shown in the adjacent cell on every row of this table. The "select all"
 * control above the table supplies its own separate, visible label instead.
 */
function RowCheckbox({
  checked,
  disabled = false,
  ariaLabel,
  onChange,
}: {
  checked: boolean;
  disabled?: boolean;
  ariaLabel: string;
  onChange: () => void;
}) {
  return (
    <span className="relative inline-flex" style={{ width: 17, height: 17 }}>
      <input
        type="checkbox"
        aria-label={ariaLabel}
        checked={checked}
        disabled={disabled}
        onChange={onChange}
        className="absolute inset-0 cursor-pointer disabled:cursor-not-allowed"
        style={{ opacity: 0, width: 17, height: 17, margin: 0 }}
      />
      <span
        aria-hidden
        className="pointer-events-none inline-flex items-center justify-center"
        style={{
          width: 17,
          height: 17,
          borderRadius: "var(--ob-radius-5)",
          border: checked ? "none" : "1px solid var(--ob-line-strong)",
          background: checked ? "var(--ob-ok-fg)" : "var(--ob-surface)",
          opacity: disabled && !checked ? 0.5 : 1,
        }}
      >
        {checked && <CheckIcon size={11} strokeWidth={3} style={{ color: "var(--ob-canvas)" }} />}
      </span>
    </span>
  );
}

/**
 * The `<900px` card (`DataTable`'s `stackedColumn`): the same checkbox,
 * customer link, eligibility pill, current stage and reason as the grid row,
 * laid out as a two-line card rather than six columns.
 */
function MigrationCard({
  candidate,
  slug,
  selected,
  onToggle,
}: {
  candidate: Candidate;
  slug: string;
  selected: Set<string>;
  onToggle: (caseId: string) => void;
}) {
  return (
    <div className="flex items-start" style={{ gap: "var(--ob-space-11)" }}>
      <div style={{ paddingTop: 2 }}>
        <RowCheckbox
          checked={Boolean(candidate.caseId && selected.has(candidate.caseId))}
          disabled={!candidate.eligible}
          ariaLabel={t("workflow.migration.selectCase", { id: candidate.caseId ?? "" })}
          onChange={() => candidate.caseId && onToggle(candidate.caseId)}
        />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between" style={{ gap: "var(--ob-space-8)" }}>
          <Link href={`/t/${slug}/customers/${candidate.customerId}`} className="truncate text-ink hover:underline" style={MONO_TEXT}>
            {shortId(candidate.customerId)}
          </Link>
          <StatusPill
            status={candidate.eligible ? t("workflow.migration.eligible") : t("workflow.migration.notEligible")}
            role={candidate.eligible ? "ok" : "risk"}
          />
        </div>
        <p className="truncate text-text-subtle" style={MONO_TEXT}>
          {[shortId(candidate.caseId), candidate.currentStageName].filter(Boolean).join(" · ")}
        </p>
        {candidate.reason && (
          <p className="text-text-muted" style={BODY_TEXT}>
            {candidate.reason}
          </p>
        )}
      </div>
    </div>
  );
}
