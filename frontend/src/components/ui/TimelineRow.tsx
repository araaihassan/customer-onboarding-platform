/**
 * Timeline row (COMPONENTS.md §8 ListRow, adapted) — `92px 22px 1fr`:
 * right-aligned mono timestamp, a dot on a vertical rule, then the event and
 * its mono meta.
 *
 * This is a purely informational activity-log entry, not the clickable
 * ListRow §8 describes: nothing here navigates or opens anything (no
 * `onClick` prop, and `TimelineTab` — its only caller — passes none), so it
 * stays an `<li>` rather than being forced into a `<button>`. §8's own rule
 * ("never a `<div>` with a click handler") guards against faking
 * interactivity, not against a row that genuinely has none. Immutable by
 * design either way: a caller composes a set of these inside a plain
 * `<ol>`/`<ul>` rather than an editable list of rows.
 */
export function TimelineRow({
  timestamp,
  actor,
  summary,
  meta,
}: {
  timestamp: string;
  actor?: string;
  summary: string;
  meta?: string;
}) {
  return (
    <li
      className="grid"
      style={{
        gridTemplateColumns: "92px 22px 1fr",
        padding: "11px 15px",
        borderBottom: "1px solid var(--ob-line-faint)",
      }}
    >
      <span
        style={{
          textAlign: "right",
          paddingRight: "var(--ob-space-8)",
          font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
          color: "var(--ob-text-muted)",
        }}
      >
        {timestamp}
      </span>
      <span className="flex justify-center" style={{ borderLeft: "1px solid var(--ob-line)" }}>
        <span
          aria-hidden
          style={{
            width: 6,
            height: 6,
            marginTop: 5,
            borderRadius: "var(--ob-radius-full)",
            background: "var(--ob-text-muted)",
          }}
        />
      </span>
      <div style={{ paddingLeft: "var(--ob-space-8)" }}>
        <p
          style={{
            font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
            color: "var(--ob-ink)",
          }}
        >
          {summary}
        </p>
        {(actor || meta) && (
          <p
            style={{
              font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
              color: "var(--ob-text-muted)",
            }}
          >
            {[actor, meta].filter(Boolean).join(" · ")}
          </p>
        )}
      </div>
    </li>
  );
}
