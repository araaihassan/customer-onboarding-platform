import type { ReactNode } from "react";
import { t } from "@/lib/i18n";

/**
 * Empty and loading states.
 *
 * Neither exists in the design — both are listed as known gaps — but every list in
 * Tasks 27 and 28 needs them, so they are built once here rather than improvised
 * per screen. The shapes follow the design's own prescription for an empty state:
 * a graphics-grey icon, a 13.5/600 line, and a muted explanation of what belongs
 * here and what fills it.
 */
export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div
      className="flex flex-col items-center text-center"
      style={{ padding: "var(--ob-space-44) var(--ob-space-20)", gap: "var(--ob-space-8)" }}
    >
      {/* The graphics-only grey. Valid at 3:1 for a 20px+ mark, never for text —
          see tokens.md on paper-600. */}
      {icon && <div style={{ color: "var(--ob-graphic-muted)" }}>{icon}</div>}
      <p className="text-text-primary" style={{ font: "600 var(--ob-type-13-5-size)/var(--ob-type-13-5-line) var(--ob-font-family-ui)" }}>
        {title}
      </p>
      {description && (
        <p className="text-text-muted" style={{ font: "var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)", maxWidth: "42ch" }}>
          {description}
        </p>
      )}
      {action}
    </div>
  );
}

/**
 * Skeleton rows at the REAL row height, so the layout does not jump when content
 * arrives. The prototype records expected counts in hint-placeholder-count
 * attributes precisely so a skeleton can match them.
 */
export function SkeletonRows({ rows = 4, height = 44 }: { rows?: number; height?: number }) {
  return (
    <div aria-busy="true" aria-live="polite" aria-label={t("common.loading")}>
      {Array.from({ length: rows }, (_, i) => (
        <div
          key={i}
          className="bg-bg-inset"
          style={{
            height,
            borderRadius: "var(--ob-radius-bar)",
            marginBottom: "var(--ob-space-8)",
            opacity: 0.6,
          }}
        />
      ))}
    </div>
  );
}
