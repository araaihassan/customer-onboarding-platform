import { t } from "@/lib/i18n";

// BuilderNode -- the workflow builder's stage node (COMPONENTS.md §21).
//
// Corrected by Task 33: this component's own comment used to claim
// `StageRow.tsx` already implemented HTML5 drag-and-drop -- it never did.
// `draftState.ts`'s own doc comment records the real, deliberate history:
// this screen has only ever reordered stages via keyboard-operable ▲▼
// buttons, never drag-and-drop. `StageRow.tsx` (Task 33) composes this
// primitive for its visual chrome and supplies those buttons itself; there is
// no `draggable`/`onDragStart`/`onDragOver`/`onDrop` wiring anywhere to be a
// caller's responsibility for. The `⋮⋮` glyph stays as a visual grouping cue
// (the real move/delete buttons sit right next to it in `StageRow`), but
// nothing here claims draggability any more -- see the removed `cursor: grab`
// below.
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
      aria-pressed={isSelected}
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
              {t("workflow.stage.conditional")}
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
