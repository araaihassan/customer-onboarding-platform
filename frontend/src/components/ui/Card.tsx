import type { ReactNode } from "react";

/**
 * The base surface for almost everything (component-specs §3).
 *
 * Cards are FLAT. Elevation is reserved for things that genuinely float —
 * popovers, open milestones, device frames — and adding a shadow to make a card
 * "pop" is one of the four decisions the design says erodes quietly.
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
      className={`bg-bg-surface border border-border-default rounded-card ${className}`}
      style={{ padding: "var(--ob-card-padding-y) var(--ob-card-padding-x)" }}
    >
      {children}
    </div>
  );
}

/**
 * Title plus an optional right-aligned count. The count is mono because it is a
 * machine-generated value; the title is not.
 */
export function CardHeader({ title, count }: { title: string; count?: number }) {
  return (
    <div className="flex items-baseline justify-between" style={{ marginBottom: "var(--ob-space-16)" }}>
      <h2 className="text-text-primary" style={{ font: "600 var(--ob-type-13-5-size)/var(--ob-type-13-5-line) var(--ob-font-family-ui)" }}>
        {title}
      </h2>
      {count !== undefined && (
        <span
          className="text-text-faint"
          style={{ font: "var(--ob-type-10-5-size)/var(--ob-type-10-5-line) var(--ob-font-family-data)" }}
        >
          {count}
        </span>
      )}
    </div>
  );
}
