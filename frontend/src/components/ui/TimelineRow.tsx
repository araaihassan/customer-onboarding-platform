/**
 * Timeline row (component-specs §15) — `92px 22px 1fr`: right-aligned mono
 * timestamp, a dot on a vertical rule, then the event and its mono meta.
 *
 * Immutable by design: an `<li>`, so a caller composes a set of these inside a
 * plain `<ol>`/`<ul>` rather than an editable list of rows.
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
    <li className="grid" style={{ gridTemplateColumns: "92px 22px 1fr" }}>
      <span
        style={{
          textAlign: "right",
          paddingRight: "var(--ob-space-8)",
          font: "var(--ob-type-10-5-size)/var(--ob-type-10-5-line) var(--ob-font-family-data)",
          color: "var(--ob-text-muted)",
        }}
      >
        {timestamp}
      </span>
      <span className="flex justify-center" style={{ borderLeft: "1px solid var(--ob-border-subtle)" }}>
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
            font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
            color: "var(--ob-text-primary)",
          }}
        >
          {summary}
        </p>
        {(actor || meta) && (
          <p
            style={{
              font: "var(--ob-type-10-size)/var(--ob-type-10-line) var(--ob-font-family-data)",
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
