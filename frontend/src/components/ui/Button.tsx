import type { ButtonHTMLAttributes } from "react";

/**
 * The primary action, at control-height and radius-control.
 *
 * No shadow. Elevation is reserved for things that genuinely float, and a raised
 * button is one of the easiest ways to start eroding that rule.
 */
export function Button({
  variant = "primary",
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" }) {
  const primary = variant === "primary";

  return (
    <button
      {...props}
      className={`inline-flex items-center justify-center ${className}`}
      style={{
        height: "var(--ob-control-height)",
        borderRadius: "var(--ob-radius-control)",
        padding: "0 var(--ob-space-16)",
        background: primary ? "var(--ob-accent)" : "var(--ob-bg-surface)",
        color: primary ? "var(--ob-text-on-accent)" : "var(--ob-text-secondary)",
        border: primary ? "1px solid var(--ob-accent)" : "1px solid var(--ob-border-default)",
        font: "500 var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)",
        // Disabled controls are exempt from the contrast requirement, but the state
        // still has to be visible — hence opacity plus the cursor.
        opacity: props.disabled ? 0.55 : 1,
        cursor: props.disabled ? "not-allowed" : "pointer",
        ...props.style,
      }}
    />
  );
}
