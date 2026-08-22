"use client";

/**
 * Switch (component-specs §12, the Inspector's toggles) — a `<button
 * role="switch">` with `aria-checked`, not a styled `<div>`. That is what makes
 * it operable by keyboard and announced correctly by a screen reader; a div
 * with a click handler gives you neither for free.
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
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className="relative inline-flex shrink-0 items-center"
      style={{
        width: 34,
        height: 20,
        borderRadius: "var(--ob-radius-pill)",
        background: checked ? "var(--ob-accent)" : "var(--ob-bg-inset)",
        padding: 2,
        border: "none",
        cursor: "pointer",
        transition: "background var(--ob-duration-fast) ease",
      }}
    >
      <span
        aria-hidden
        style={{
          width: 16,
          height: 16,
          borderRadius: "var(--ob-radius-full)",
          background: "var(--ob-bg-surface)",
          transform: checked ? "translateX(14px)" : "translateX(0)",
          transition: "transform var(--ob-duration-fast) ease",
        }}
      />
    </button>
  );
}
