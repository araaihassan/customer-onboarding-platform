"use client";

import { CheckIcon } from "@/components/icons";

/**
 * Checkbox (component-specs §11) — a real `<input type="checkbox">`, not a
 * styled `<button>`. Review finding: the prototype's task checkbox was a button
 * with no checked state at all; screen readers and keyboards get the checked
 * state, focus ring and Space-to-toggle for free from the native element, and
 * only the paint is custom.
 *
 * `busy` disables the control while a mutation is in flight — satisfying a
 * requirement is a server round-trip, not local state, and a second click
 * would fire a second mutation against a state the first has not returned yet.
 */
export function Checkbox({
  checked,
  onChange,
  label,
  busy = false,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  busy?: boolean;
}) {
  return (
    <label className="inline-flex items-center" style={{ gap: "var(--ob-space-8)" }}>
      <span className="relative inline-flex" style={{ width: 17, height: 17 }}>
        <input
          type="checkbox"
          checked={checked}
          disabled={busy}
          onChange={(e) => onChange(e.target.checked)}
          className="absolute inset-0 cursor-pointer disabled:cursor-not-allowed"
          style={{ opacity: 0, width: 17, height: 17, margin: 0 }}
        />
        <span
          aria-hidden
          className="pointer-events-none inline-flex items-center justify-center"
          style={{
            width: 17,
            height: 17,
            borderRadius: "var(--ob-radius-5)",
            border: checked ? "none" : "1px solid var(--ob-paper-500)",
            background: checked ? "var(--ob-ok-fg)" : "var(--ob-bg-surface)",
          }}
        >
          {checked && <CheckIcon size={11} strokeWidth={3} style={{ color: "var(--ob-text-on-solid)" }} />}
        </span>
      </span>
      <span
        style={{
          font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
          color: checked ? "var(--ob-text-disabled)" : "var(--ob-text-primary)",
          textDecoration: checked ? "line-through" : "none",
        }}
      >
        {label}
      </span>
    </label>
  );
}
