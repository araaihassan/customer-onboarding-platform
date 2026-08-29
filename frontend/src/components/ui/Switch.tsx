"use client";

import { useId } from "react";

/**
 * Switch (component-specs §12, the Inspector's toggles) — a `<button
 * role="switch">` with `aria-checked`, not a styled `<div>`. That is what makes
 * it operable by keyboard and announced correctly by a screen reader; a div
 * with a click handler gives you neither for free.
 *
 * `label` renders as real, visible text (via aria-labelledby, so the sighted
 * and the accessible name never disagree) -- a switch with no visible label is
 * a control nobody can identify from the screen, caught by actually looking at
 * the rendered Inspector rather than by a unit test asserting aria-label alone.
 */
export function Switch({
  checked,
  onChange,
  label,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
}) {
  const labelId = useId();

  return (
    <div className="flex items-center justify-between">
      <span
        id={labelId}
        className="text-text-muted"
        style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
      >
        {label}
      </span>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-labelledby={labelId}
        onClick={() => onChange(!checked)}
        className="relative inline-flex shrink-0 items-center"
        style={{
          width: 34,
          height: 19,
          borderRadius: "var(--ob-radius-11)",
          background: checked ? "var(--ob-accent-fg)" : "var(--ob-line-strong)",
          padding: 2,
          border: "none",
          cursor: "pointer",
          transition: "background var(--ob-duration-pop) ease",
        }}
      >
        <span
          aria-hidden
          style={{
            width: 15,
            height: 15,
            borderRadius: "var(--ob-radius-full)",
            background: "var(--ob-surface)",
            transform: checked ? "translateX(15px)" : "translateX(0)",
            transition: "transform var(--ob-duration-pop) ease",
          }}
        />
      </button>
    </div>
  );
}
