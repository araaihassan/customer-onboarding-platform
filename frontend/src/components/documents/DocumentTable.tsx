import Link from "next/link";
import { FileTextIcon, LockIcon } from "@/components/icons";
import { DataTable } from "@/components/ui/DataTable";
import { EmptyState } from "@/components/ui/States";
import { humanise } from "@/components/ui/StatusPill";
import { shortId } from "@/lib/api/customers";
import type { Document } from "@/lib/api/documents";
import { t } from "@/lib/i18n";
import { VisibilityCell } from "./VisibilityCell";

/**
 * The `docs` table (`SCREENS.md` §7), Task 31 -- the operator-facing,
 * tenant-wide documents index (`useDocuments`, already built). Grid
 * `34px 1.6fr 1fr .9fr 1fr` = tile / Document / Customer / Category /
 * Visibility, following `MigrationTable.tsx`'s own `DataTable`-composition
 * shape (its own doc comment names it the closest existing precedent for
 * this table): the same `BODY_TEXT`/`MONO_TEXT` style-object pattern, the
 * same `EMPTY` em-dash constant, `framed={false}` when the caller already
 * wraps this in its own card, `stackedColumn` for the <900px card fallback.
 *
 * **Ruling 3.** `SCREENS.md` §17's row spec for a visually similar table
 * describes a file-type tile (`PDF`/`XLS`/`REQ`), but that code comes from
 * `DocumentVersion.contentType`, a field `DocumentView` does not carry, and
 * this task's one hook (`useDocuments`) never fetches version data. The tile
 * column therefore renders a plain, neutral 34x34px radius-5 `FileTextIcon`
 * for every row -- a deliberate substitution for missing data, not an
 * oversight, so a future task that DOES fetch version-level content-type
 * knows exactly where a real per-row file-type label belongs.
 *
 * The Customer column renders `shortId(customerId)` only, the same shape
 * `MigrationTable`'s own Customer column already uses -- `DocumentView`
 * carries no customer name field (confirmed against `generated.ts` and the
 * design's own `STATE_AND_DATA.md` data shape, which likewise has only
 * `accountId`), so a name would be exactly as fabricated as Ruling 1's
 * rejected fourth visibility label.
 *
 * The Category column reuses `StatusPill`'s exported `humanise` (Task 31),
 * the one enum-humanising convention already established in this codebase,
 * rather than inventing a second, differently-cased transform.
 */
export function DocumentTable({
  documents,
  slug,
}: {
  documents: Document[];
  slug: string;
}) {
  if (documents.length === 0) {
    return (
      <EmptyState
        icon={<FileTextIcon size={28} />}
        title={t("documents.table.empty.title")}
        description={t("documents.table.empty.description")}
      />
    );
  }

  const columns = [
    {
      key: "tile",
      label: "",
      width: "34px",
      render: () => (
        <span
          aria-hidden
          className="inline-flex items-center justify-center bg-surface-sunken text-text-subtle"
          style={{ width: 34, height: 34, borderRadius: "var(--ob-radius-5)" }}
        >
          <FileTextIcon size={16} />
        </span>
      ),
    },
    {
      key: "document",
      label: t("documents.table.document"),
      width: "1.6fr",
      render: (document: Document) => (
        <span className="inline-flex items-center text-ink" style={{ gap: "var(--ob-space-6)", ...NAME_TEXT }}>
          {document.visibilityTier === "SENSITIVE" && <LockIcon size={13} />}
          <span className="truncate">{document.name || EMPTY}</span>
        </span>
      ),
    },
    {
      key: "customer",
      label: t("documents.table.customer"),
      width: "1fr",
      render: (document: Document) =>
        document.customerId ? (
          <Link href={`/t/${slug}/customers/${document.customerId}`} className="text-ink hover:underline">
            <span style={MONO_TEXT}>{shortId(document.customerId)}</span>
          </Link>
        ) : (
          <span style={MONO_TEXT}>{EMPTY}</span>
        ),
    },
    {
      key: "category",
      label: t("documents.table.category"),
      width: ".9fr",
      render: (document: Document) => (
        <span className="text-text-2" style={BODY_TEXT}>
          {document.category ? humanise(document.category) : EMPTY}
        </span>
      ),
    },
    {
      key: "visibility",
      label: t("documents.table.visibility"),
      width: "1fr",
      render: (document: Document) => <VisibilityCell tier={document.visibilityTier} />,
    },
  ];

  return (
    <DataTable
      columns={columns}
      rows={documents}
      getRowKey={(document) => document.id ?? ""}
      stackedColumn={(document) => <DocumentCard document={document} slug={slug} />}
    />
  );
}

/** An em dash, so a missing optional value reads as absent rather than broken -- same convention as `MigrationTable`. */
const EMPTY = "—";

/** Table cell (`DESIGN_TOKENS.md` Typography: 12.5px/400). */
const BODY_TEXT = {
  font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;

/** The identifying column: 12.5-13px/600 (`COMPONENTS.md` §12). Human text (a filename), so Instrument Sans, not mono. */
const NAME_TEXT = {
  font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;

/** Mono data (11px) -- customer id. */
const MONO_TEXT = {
  font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
} as const;

/** The <900px card (`DataTable`'s `stackedColumn`): tile + name + lock glyph, customer link, category and the visibility cell, laid out as a compact card rather than five columns. */
function DocumentCard({ document, slug }: { document: Document; slug: string }) {
  return (
    <div className="flex items-start" style={{ gap: "var(--ob-space-11)" }}>
      <span
        aria-hidden
        className="inline-flex items-center justify-center bg-surface-sunken text-text-subtle"
        style={{ width: 34, height: 34, borderRadius: "var(--ob-radius-5)", flexShrink: 0 }}
      >
        <FileTextIcon size={16} />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between" style={{ gap: "var(--ob-space-8)" }}>
          <span className="inline-flex items-center truncate text-ink" style={{ gap: "var(--ob-space-6)", ...NAME_TEXT }}>
            {document.visibilityTier === "SENSITIVE" && <LockIcon size={13} />}
            <span className="truncate">{document.name || EMPTY}</span>
          </span>
        </div>
        <p className="truncate text-text-subtle" style={MONO_TEXT}>
          {document.customerId ? (
            <Link href={`/t/${slug}/customers/${document.customerId}`} className="hover:underline">
              {shortId(document.customerId)}
            </Link>
          ) : (
            EMPTY
          )}
          {document.category ? ` · ${humanise(document.category)}` : ""}
        </p>
        <div style={{ marginTop: "var(--ob-space-4)" }}>
          <VisibilityCell tier={document.visibilityTier} />
        </div>
      </div>
    </div>
  );
}
