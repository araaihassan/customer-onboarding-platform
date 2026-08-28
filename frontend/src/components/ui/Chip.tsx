import type { ButtonHTMLAttributes, ReactNode } from "react";

/**
 * Chip (COMPONENTS.md §3) — the workhorse: always uppercase mono, never
 * bordered. Active is "inverted": `ink` fill, `canvas` text; inactive is the
 * neutral status pair, `neutral-bg`/`neutral-fg`.
 *
 * A filter SET is the caller's responsibility: wrap several of these in a
 * `<div role="group">` with an accessible name, per the spec's own accessibility
 * note -- a single chip has no group to belong to.
 */
export function Chip({
  active = false,
  dot,
  mono,
  className = "",
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  active?: boolean;
  /** A status dot rendered before the label — the case chip's own affordance. */
  dot?: ReactNode;
  /** Trailing content rendered in the mono data font, e.g. a 9.5px case id. */
  mono?: ReactNode;
}) {
  return (
    <button
      type="button"
      {...props}
      aria-pressed={active}
      className={`inline-flex items-center ${className}`}
      style={{
        height: "auto",
        padding: "2px 6px",
        borderRadius: "var(--ob-radius-5)",
        border: "none",
        gap: "5px",
        font: `400 var(--ob-type-mono-chip-size)/var(--ob-type-mono-chip-line) var(--ob-font-family-data)`,
        letterSpacing: "var(--ob-type-mono-chip-tracking)",
        textTransform: "uppercase",
        background: active ? "var(--ob-ink)" : "var(--ob-neutral-bg)",
        color: active ? "var(--ob-canvas)" : "var(--ob-neutral-fg)",
        cursor: props.onClick ? "pointer" : "default",
        ...props.style,
      }}
    >
      {dot}
      <span>{children}</span>
      {mono !== undefined && (
        <span
          style={{
            font: `400 var(--ob-type-mono-chip-size)/var(--ob-type-mono-chip-line) var(--ob-font-family-data)`,
            letterSpacing: "var(--ob-type-mono-chip-tracking)",
          }}
        >
          {mono}
        </span>
      )}
    </button>
  );
}
