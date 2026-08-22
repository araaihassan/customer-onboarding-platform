import type { ButtonHTMLAttributes, ReactNode } from "react";

/**
 * Chip (component-specs §8) — the filter chip and the case chip share this one
 * shape. Active is "inverted": text-primary fill, bg-surface text, exactly the
 * spec's own wording for both variants.
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
        height: "var(--ob-control-height-sm)",
        borderRadius: "var(--ob-radius-chip)",
        padding: "0 13px",
        gap: "5px",
        background: active ? "var(--ob-text-primary)" : "var(--ob-bg-surface)",
        color: active ? "var(--ob-bg-surface)" : "var(--ob-text-secondary)",
        border: `1px solid var(${active ? "--ob-text-primary" : "--ob-border-default"})`,
        font: "var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)",
        cursor: "pointer",
        ...props.style,
      }}
    >
      {dot}
      <span>{children}</span>
      {mono !== undefined && (
        <span
          style={{
            font: "var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
          }}
        >
          {mono}
        </span>
      )}
    </button>
  );
}
