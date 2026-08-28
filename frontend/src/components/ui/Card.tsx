import type { ReactNode } from "react";

/**
 * The base surface for almost everything (component-specs §3).
 *
 * Cards are FLAT. Elevation is reserved for things that genuinely float —
 * popovers, open milestones, device frames — and a shadow that makes a card
 * "pop" is one of the four decisions the design says erodes quietly. The `card`
 * shadow token below is not that: at `rgba(28,27,24,.03)` it reads as a soft
 * edge, not a lift, and DESIGN_TOKENS.md's own Shadows table assigns it to
 * "every card" explicitly — this is the design system's one deliberate,
 * barely-perceptible exception, not an invitation to reach for a bigger one.
 */
export function Card({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={`bg-surface border border-line rounded-11 ${className}`}
      style={{ padding: "14px 15px 14px 15px", boxShadow: "var(--ob-shadow-card)" }}
    >
      {children}
    </div>
  );
}

/**
 * Title plus an optional right-aligned count, and an optional action beside it.
 * The count is mono because it is a machine-generated value; the title is not.
 *
 * `items-center` when there is an action and `items-baseline` when there is not:
 * a control aligned on the title's baseline sits visibly low, while a bare count
 * aligned on the control's centre sits visibly high.
 */
export function CardHeader({
  title,
  count,
  action,
}: {
  title: string;
  count?: number;
  action?: ReactNode;
}) {
  return (
    <div
      className={`flex justify-between ${action ? "items-center" : "items-baseline"}`}
      style={{ marginBottom: "var(--ob-space-16)" }}
    >
      <h2 className="text-text-primary" style={{ font: "600 var(--ob-type-card-title-size)/var(--ob-type-card-title-line) var(--ob-font-family-ui)" }}>
        {title}
      </h2>
      <div className="flex items-center" style={{ gap: "var(--ob-space-11)" }}>
        {count !== undefined && (
          <span
            className="text-text-faint"
            style={{ font: "var(--ob-type-10-5-size)/var(--ob-type-10-5-line) var(--ob-font-family-data)" }}
          >
            {count}
          </span>
        )}
        {action}
      </div>
    </div>
  );
}
