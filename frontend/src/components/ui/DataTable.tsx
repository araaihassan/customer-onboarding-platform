import type { ReactNode } from "react";

type Column<T> = {
  key: string;
  label: string;
  align?: "left" | "right";
  width?: string;
  render?: (row: T) => ReactNode;
};

export function DataTable<T>({
  columns,
  rows,
  getRowKey,
  onRowClick,
  footer,
  stackedColumn,
  framed = true,
}: {
  columns: Column<T>[];
  rows: T[];
  getRowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
  footer?: ReactNode;
  /**
   * Below 900px (`SCREENS.md`'s RESPONSIVE table: "tables switch to stacked
   * cards keyed by the identifying column") the grid is replaced by one card
   * per row, rendered from this function -- the caller's own markup, not a
   * generic re-stack of `columns`, since a card's identifying content (name,
   * status, a reference line) is usually assembled from several columns at
   * once, not one column per line. Both presentations stay mounted
   * simultaneously (CSS `hidden`/`min-[900px]:` visibility, not conditional
   * rendering) so switching between them can never drop a row -- the same
   * empty-state-guard bug class `MigrationTable` once had, avoided here by
   * construction rather than a length check.
   */
  stackedColumn?: (row: T) => ReactNode;
  /**
   * Whether the table wrapper carries its own card frame
   * (`bg-surface border border-line rounded-11 overflow-hidden`). Defaults to
   * `true` so existing callers (`CustomerTable`) need no change. A caller
   * that already wraps `DataTable` in its own frame -- `MigrationTable`'s
   * page does this today, and would otherwise end up double-bordered the
   * moment it converts to this primitive -- passes `framed={false}` to opt
   * out and supply its own instead.
   */
  framed?: boolean;
}) {
  const gridTemplate = columns.map((c) => c.width ?? "1fr").join(" ");

  return (
    <>
      <div
        data-view="table"
        role="table"
        className={`${framed ? "bg-surface border border-line rounded-11 overflow-hidden" : ""} ${stackedColumn ? "hidden min-[900px]:block" : ""}`}
        style={{ overflowX: "auto" }}
      >
        <div style={{ minWidth: "fit-content" }}>
          <div role="rowgroup">
            <div
              role="row"
              style={{
                display: "grid",
                gridTemplateColumns: gridTemplate,
                gap: "var(--ob-space-12)",
                padding: "9px 15px",
                background: "var(--ob-surface-sunken)",
                borderBottom: "1px solid var(--ob-line)",
              }}
            >
              {columns.map((col) => (
                <span
                  key={col.key}
                  role="columnheader"
                  data-column={col.key}
                  style={{
                    font: `500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)`,
                    letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
                    textTransform: "uppercase",
                    color: "var(--ob-text-subtle)",
                    textAlign: col.align ?? "left",
                  }}
                >
                  {col.label}
                </span>
              ))}
            </div>
          </div>
          <div role="rowgroup">
            {rows.map((row) => {
              const cells = columns.map((col) => (
                <span key={col.key} role="cell" data-column={col.key} style={{ textAlign: col.align ?? "left" }}>
                  {col.render ? col.render(row) : String((row as Record<string, unknown>)[col.key] ?? "")}
                </span>
              ));
              const rowStyle = {
                display: "grid",
                gridTemplateColumns: gridTemplate,
                gap: "var(--ob-space-12)",
                padding: "10px 15px",
                borderBottom: "1px solid var(--ob-line-faint)",
                alignItems: "center",
                width: "100%",
                textAlign: "left" as const,
                font: `13px/1.4 var(--ob-font-family-ui)`,
                color: "var(--ob-ink)",
              };
              return onRowClick ? (
                <button
                  key={getRowKey(row)}
                  type="button"
                  role="row"
                  onClick={() => onRowClick(row)}
                  className="hover:bg-surface-sunken"
                  style={rowStyle}
                >
                  {cells}
                </button>
              ) : (
                <div key={getRowKey(row)} role="row" style={rowStyle}>
                  {cells}
                </div>
              );
            })}
          </div>
          {footer && (
            <div
              style={{
                padding: "10px 15px",
                background: "var(--ob-surface-sunken)",
                font: `var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)`,
              }}
            >
              {footer}
            </div>
          )}
        </div>
      </div>

      {stackedColumn && (
        <ul data-view="cards" className="min-[900px]:hidden flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
          {rows.map((row) => (
            <li
              key={getRowKey(row)}
              className="bg-surface border border-line rounded-11"
              style={{ padding: "var(--ob-space-13) var(--ob-space-16)" }}
            >
              {stackedColumn(row)}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
