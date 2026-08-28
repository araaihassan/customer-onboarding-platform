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
}: {
  columns: Column<T>[];
  rows: T[];
  getRowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
  footer?: ReactNode;
}) {
  const gridTemplate = columns.map((c) => c.width ?? "1fr").join(" ");

  return (
    <div style={{ overflowX: "auto" }}>
      <div style={{ minWidth: "fit-content" }}>
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
        {rows.map((row) => {
          const cells = columns.map((col) => (
            <span key={col.key} style={{ textAlign: col.align ?? "left" }}>
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
              onClick={() => onRowClick(row)}
              className="hover:bg-surface-sunken"
              style={rowStyle}
            >
              {cells}
            </button>
          ) : (
            <div key={getRowKey(row)} style={rowStyle}>
              {cells}
            </div>
          );
        })}
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
  );
}
