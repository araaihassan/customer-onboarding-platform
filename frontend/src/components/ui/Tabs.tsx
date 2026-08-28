"use client";

import type { KeyboardEvent } from "react";

export type TabItem = {
  id: string;
  label: string;
  /** A count shown beside the label, e.g. "Tasks (8)". Decorative -- see below. */
  badge?: number;
};

/**
 * Tabs (component-specs §9) — `role="tablist"`/`tab`, automatic activation:
 * arrow keys move focus AND select in the same step, matching the simpler of
 * the two WAI-ARIA tab patterns. The caller renders its own
 * `role="tabpanel"` elements; this component only owns the strip.
 *
 * The badge is `aria-hidden`: "Tasks (8)" must announce as "Tasks", not
 * "Tasks 8" -- the count is a supporting visual, not part of the tab's name.
 */
export function Tabs({
  items,
  value,
  onChange,
}: {
  items: TabItem[];
  value: string;
  onChange: (id: string) => void;
}) {
  function move(from: number, delta: number) {
    // The modulo always lands inside [0, items.length): non-null by construction.
    const next = items[(from + delta + items.length) % items.length]!;
    onChange(next.id);
    document.getElementById(tabId(next.id))?.focus();
  }

  function handleKeyDown(e: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (e.key === "ArrowRight") {
      e.preventDefault();
      move(index, 1);
    } else if (e.key === "ArrowLeft") {
      e.preventDefault();
      move(index, -1);
    }
  }

  return (
    <div
      style={{
        background: "var(--ob-surface-active)",
        border: "1px solid var(--ob-line)",
        borderRadius: "var(--ob-radius-9)",
        padding: "3px",
      }}
    >
      <div role="tablist" className="flex w-full" style={{ gap: "4px" }}>
        {items.map((item, index) => {
          const selected = item.id === value;
          return (
            <button
              key={item.id}
              id={tabId(item.id)}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls={panelId(item.id)}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(item.id)}
              onKeyDown={(e) => handleKeyDown(e, index)}
              style={{
                height: "27px",
                padding: "0 12px",
                border: "0",
                borderRadius: "var(--ob-radius-6)",
                font: `500 12.5px/1.2 var(--ob-font-family-ui)`,
                background: selected ? "var(--ob-surface)" : "transparent",
                color: selected ? "var(--ob-ink)" : "var(--ob-text-muted)",
                cursor: "pointer",
              }}
            >
              {item.label}
              {item.badge !== undefined && (
                <span
                  aria-hidden
                  style={{
                    marginLeft: 4,
                    font: `500 var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)`,
                  }}
                >
                  ({item.badge})
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function tabId(id: string) {
  return `tab-${id}`;
}

/** The id a caller's `role="tabpanel"` element must carry for aria-controls to resolve. */
export function panelId(id: string) {
  return `tabpanel-${id}`;
}
