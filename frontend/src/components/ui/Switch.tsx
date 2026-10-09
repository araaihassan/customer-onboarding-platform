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
  disabled = false,
  ariaLabel,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  /** Rendered as aria-disabled (stays focusable and announced); onChange never fires. */
  disabled?: boolean;
  /** When present it is the accessible name and aria-labelledby is omitted (it would take precedence). */
  ariaLabel?: string;
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
        aria-label={ariaLabel}
        aria-labelledby={ariaLabel ? undefined : labelId}
        aria-disabled={disabled ? "true" : undefined}
        onClick={() => {
          if (!disabled) onChange(!checked);
        }}
        className="relative inline-flex shrink-0 items-center"
        style={{
          width: 34,
          height: 19,
          borderRadius: "var(--ob-radius-11)",
          background: checked ? "var(--ob-accent-fg)" : "var(--ob-line-strong)",
          padding: 2,
          border: "none",
          cursor: disabled ? "not-allowed" : "pointer",
          opacity: disabled ? 0.55 : 1,
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
            boxShadow: "0 1px 2px rgba(0,0,0,.2)",
            transform: checked ? "translateX(15px)" : "translateX(0)",
            transition: "transform var(--ob-duration-pop) ease",
          }}
        />
      </button>
    </div>
  );
}
