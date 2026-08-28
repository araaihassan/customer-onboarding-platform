// BuilderNode -- the workflow builder's draggable stage node (COMPONENTS.md §21).
//
// Drag-and-drop wiring (`draggable`, `onDragStart`/`onDragOver`/`onDrop`) stays the
// caller's responsibility -- `StageRow.tsx` already implements it, and Task 31
// converts that component to compose this primitive. The keyboard-accessible
// reorder affordance COMPONENTS.md §21 describes (move-up/move-down buttons) is
// deferred to that same conversion, since it needs the real reorder logic to
// attach to -- it is not invented here.
export function BuilderNode({
  name,
  teamMeta,
  milestonePills,
  isBranch = false,
  isSelected = false,
  isDragging = false,
  conditionalChip = false,
  onClick,
}: {
  name: string;
  teamMeta: string;
  milestonePills: string[];
  isBranch?: boolean;
  isSelected?: boolean;
  isDragging?: boolean;
  conditionalChip?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        width: "100%",
        display: "flex",
        alignItems: "center",
        gap: "var(--ob-space-12)",
        borderRadius: "var(--ob-radius-10)",
        padding: "11px 13px",
        background: isBranch ? "#faf7ff" : "var(--ob-surface)",
        border: `1px solid ${isSelected ? "var(--ob-ink)" : isBranch ? "var(--ob-automation-border)" : "var(--ob-line)"}`,
        boxShadow: isSelected ? "var(--ob-shadow-ring-selected)" : "var(--ob-shadow-card)",
        opacity: isDragging ? 0.4 : 1,
        cursor: "grab",
        textAlign: "left",
      }}
    >
      <span aria-hidden="true" style={{ color: "var(--ob-text-ghost)" }}>⋮⋮</span>
      <span
        aria-hidden="true"
        style={{
          width: 24, height: 24, borderRadius: "var(--ob-radius-7)",
          display: "grid", placeItems: "center",
          background: isBranch ? "var(--ob-automation-fg)" : isSelected ? "var(--ob-ink)" : "var(--ob-surface-active)",
          color: isBranch || isSelected ? "var(--ob-canvas)" : "var(--ob-text-muted)",
          font: "600 11px/1 var(--ob-font-family-ui)",
        }}
      >
        {isBranch ? "⑃" : ""}
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: "var(--ob-space-8)" }}>
          <span style={{ font: "600 13.5px/1.3 var(--ob-font-family-ui)", color: "var(--ob-ink)" }}>{name}</span>
          {conditionalChip && (
            <span
              style={{
                font: `400 var(--ob-type-mono-chip-size)/var(--ob-type-mono-chip-line) var(--ob-font-family-data)`,
                textTransform: "uppercase",
                background: "var(--ob-automation-bg)",
                color: "var(--ob-automation-fg)",
                borderRadius: "var(--ob-radius-5)",
                padding: "2px 6px",
              }}
            >
              CONDITIONAL
            </span>
          )}
        </div>
        {teamMeta && (
          <div style={{ font: "11.5px/1.3 var(--ob-font-family-ui)", color: "var(--ob-text-subtle)" }}>{teamMeta}</div>
        )}
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: "var(--ob-space-6)", maxWidth: "44%", justifyContent: "flex-end" }}>
        {milestonePills.map((pill) => (
          <span
            key={pill}
            style={{
              font: "10.5px/1.3 var(--ob-font-family-ui)",
              background: "var(--ob-surface-active)",
              border: "1px solid var(--ob-line)",
              borderRadius: "var(--ob-radius-5)",
              padding: "2px 6px",
            }}
          >
            {pill}
          </span>
        ))}
      </div>
    </button>
  );
}
