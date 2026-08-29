import type { ButtonHTMLAttributes } from "react";

/**
 * The shared button primitive, restyled to COMPONENTS.md §4's 9-variant spec.
 *
 * Primary actions are ink-colored (near-black), not accent-colored -- colour is
 * reserved for meaning, per DESIGN_TOKENS.md. No shadow: elevation is reserved
 * for things that genuinely float, and a raised button is one of the easiest
 * ways to start eroding that rule.
 *
 * `hoverBg` values below are documented for a future CSS-module/class-based
 * hover pass -- inline styles can't express `:hover`, so hover is not wired up
 * here. This is a pre-existing gap (the old two-variant Button had no hover
 * styling either), not newly introduced by this restyle.
 */
type Variant =
  | "primary" | "secondary" | "small-primary" | "small-secondary"
  | "danger-outline" | "filter-active" | "filter-idle" | "portal-primary" | "text-link";

const VARIANTS: Record<Variant, {
  height: string; padding: string; background: string; color: string;
  border: string; radius: string; font: string; hoverBg?: string;
}> = {
  primary: { height: "var(--ob-control-height)", padding: "0 13px", background: "var(--ob-ink)", color: "var(--ob-canvas)", border: "none", radius: "var(--ob-radius-8)", font: "600 12.5px/1.2", hoverBg: "var(--ob-ink-hover)" },
  secondary: { height: "var(--ob-control-height)", padding: "0 12px", background: "var(--ob-surface)", color: "var(--ob-ink)", border: "1px solid var(--ob-line)", radius: "var(--ob-radius-8)", font: "500 12.5px/1.2", hoverBg: "var(--ob-surface-active)" },
  "small-primary": { height: "var(--ob-control-height-sm)", padding: "0 11px", background: "var(--ob-ink)", color: "var(--ob-canvas)", border: "none", radius: "var(--ob-radius-7)", font: "600 11.5px/1.2", hoverBg: "var(--ob-ink-hover)" },
  "small-secondary": { height: "var(--ob-control-height-sm)", padding: "0 10px", background: "var(--ob-surface)", color: "var(--ob-ink)", border: "1px solid var(--ob-line)", radius: "var(--ob-radius-7)", font: "500 11.5px/1.2", hoverBg: "var(--ob-surface-active)" },
  "danger-outline": { height: "27px", padding: "0 10px", background: "var(--ob-surface)", color: "var(--ob-risk-fg)", border: "1px solid var(--ob-risk-border)", radius: "var(--ob-radius-7)", font: "600 12px/1.2", hoverBg: "var(--ob-risk-bg)" },
  "filter-active": { height: "30px", padding: "0 11px", background: "var(--ob-ink)", color: "var(--ob-canvas)", border: "1px solid var(--ob-line)", radius: "var(--ob-radius-8)", font: "500 12.5px/1.2" },
  "filter-idle": { height: "30px", padding: "0 11px", background: "var(--ob-surface)", color: "var(--ob-ink)", border: "1px solid var(--ob-line)", radius: "var(--ob-radius-8)", font: "500 12.5px/1.2", hoverBg: "var(--ob-surface-active)" },
  "portal-primary": { height: "36px", padding: "0 16px", background: "var(--ob-ink)", color: "var(--ob-canvas)", border: "none", radius: "var(--ob-radius-9)", font: "600 13px/1.2", hoverBg: "var(--ob-ink-hover)" },
  "text-link": { height: "auto", padding: "0", background: "transparent", color: "var(--ob-accent-fg)", border: "none", radius: "0", font: "600 12.5px/1.2" },
};

export function Button({
  variant = "primary",
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  const v = VARIANTS[variant];
  return (
    <button
      {...props}
      className={`inline-flex items-center justify-center ${variant === "text-link" ? "hover:underline" : ""} ${className}`}
      style={{
        height: v.height,
        padding: v.padding,
        borderRadius: v.radius,
        background: props.disabled ? "var(--ob-line)" : v.background,
        color: props.disabled ? "var(--ob-text-faint)" : v.color,
        // Omit the property entirely for a borderless variant rather than
        // writing the literal "none" -- setting `border: "none"` still
        // populates border-width (medium) as a side effect of the shorthand,
        // so a border-less button's el.style.border would read back as
        // "medium" instead of "" the way a control with no border at all
        // (never assigned a border property) does.
        ...(v.border !== "none" ? { border: v.border } : {}),
        font: `${v.font} var(--ob-font-family-ui)`,
        cursor: props.disabled ? "not-allowed" : "pointer",
        ...props.style,
      }}
    />
  );
}
