import { Button } from "@/components/ui/Button";
import type { DocumentVisibilityTier } from "@/lib/api/documents";
import { t } from "@/lib/i18n";

/**
 * The `docs` screen's five scope filters (`SCREENS.md` §7): "All in scope ·
 * Company-shared · Contact-only · Sensitive · Open requests." `"ALL"` means no
 * `visibilityTier` filter at all -- the same `undefined`/`null`-means-every-tier
 * contract `DocumentService.list`/`useDocuments` already carry (Task 32's own
 * backend half); `OPEN_REQUESTS` is a distinct, never-selectable fifth value
 * (see below), not a fourth real tier.
 */
export type ScopeFilterValue = "ALL" | DocumentVisibilityTier | "OPEN_REQUESTS";

const TIER_FILTERS: ReadonlyArray<{ value: "ALL" | DocumentVisibilityTier; labelKey: string }> = [
  { value: "ALL", labelKey: "documents.filter.all" },
  { value: "COMPANY_SHARED", labelKey: "documents.filter.companyShared" },
  { value: "CONTACT_ONLY", labelKey: "documents.filter.contactOnly" },
  { value: "SENSITIVE", labelKey: "documents.filter.sensitive" },
];

/**
 * A controlled filter set -- `active`/`onChange` are the caller's, following
 * `WorkBoard.tsx`'s own `activeBucket`/`setBucket` shape for the identical
 * "Filter (active)/(idle)" button pair (`COMPONENTS.md` §4), which is the
 * correct precedent to copy here: this row is genuinely a toggle-button
 * filter set, the shape `ui/Button.tsx`'s `filter-active`/`filter-idle`
 * variants already exist for and `WorkBoard` already uses live, not
 * `Chip.tsx`'s own always-uppercase-mono, never-bordered "workhorse" shape
 * (Task 31's `VisibilityCell` uses THAT one correctly, for a status label
 * inside a table cell -- a different job).
 *
 * **Ruling 4 (see `task-32-brief.md`).** "Open requests" has no backing data
 * yet -- no `document_request` list endpoint exists anywhere (Task 30's own
 * `useDocumentRequests` doc comment already records this gap for the
 * case-scoped read; there is no tenant-wide one either). All five buttons
 * `SCREENS.md` §7 names are still rendered -- CLAUDE.md requires implementing
 * the design as specified, not silently dropping a row -- but this one is
 * `disabled` and carries no `onClick`/`onChange` call at all, so it can never
 * report a selection nothing can actually filter by.
 */
export function ScopeFilterRow({
  active,
  onChange,
}: {
  active: ScopeFilterValue;
  onChange: (value: "ALL" | DocumentVisibilityTier) => void;
}) {
  return (
    <div
      role="group"
      aria-label={t("documents.filter.label")}
      className="flex flex-wrap items-center"
      style={{ gap: "var(--ob-space-6)" }}
    >
      {TIER_FILTERS.map((filter) => (
        <Button
          key={filter.value}
          type="button"
          variant={active === filter.value ? "filter-active" : "filter-idle"}
          aria-pressed={active === filter.value}
          onClick={() => onChange(filter.value)}
        >
          {t(filter.labelKey)}
        </Button>
      ))}
      <Button
        type="button"
        variant="filter-idle"
        aria-pressed={false}
        disabled
        // No backend list endpoint exists to filter by -- see this
        // component's own doc comment (Ruling 4). Disabled rather than
        // omitted: SCREENS.md §7 names five filters, and CLAUDE.md's design
        // system section requires implementing what is specified.
        title={t("documents.filter.openRequests.disabledReason")}
      >
        {t("documents.filter.openRequests")}
      </Button>
    </div>
  );
}
