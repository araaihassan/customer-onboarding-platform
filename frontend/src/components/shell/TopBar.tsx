"use client";

import { usePageHeader } from "./PageHeader";

/**
 * The shell top bar (COMPONENTS.md §1's "Top bar"). Presence cluster, inbox
 * button and a contextual primary action are specified there but have no
 * current counterpart -- no presence tracking, no inbox, no per-screen
 * "primary action" concept exists yet, so the right side is empty rather than
 * a dead control. Same principle this file already applied to search/
 * notifications before this restyle.
 */
export function TopBar() {
  const { title, meta } = usePageHeader();

  return (
    <header
      className="sticky top-0 z-30 flex items-center border-b"
      style={{
        height: "var(--ob-topbar-height)",
        padding: "0 18px",
        gap: "var(--ob-space-12)",
        borderColor: "var(--ob-line)",
        background: "var(--ob-canvas)",
      }}
    >
      {title && (
        <span
          className="truncate min-w-0 uppercase"
          style={{
            font: `var(--ob-type-breadcrumb-size)/var(--ob-type-breadcrumb-line) var(--ob-font-family-data)`,
            letterSpacing: "var(--ob-type-breadcrumb-tracking)",
            color: "var(--ob-text-subtle)",
          }}
        >
          {title}
        </span>
      )}
      {meta && (
        <span
          className="overflow-hidden text-ellipsis whitespace-nowrap"
          style={{
            font: `var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)`,
            color: "var(--ob-text-muted)",
          }}
        >
          {meta}
        </span>
      )}
      <div className="flex-1" />
    </header>
  );
}
