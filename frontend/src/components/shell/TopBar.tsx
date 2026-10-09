"use client";

import { InboxButton } from "@/components/inbox/InboxButton";
import { usePageHeader } from "./PageHeader";

/**
 * The shell top bar (COMPONENTS.md section 1's "Top bar"). The Inbox control
 * (6B) sits at the right; the presence cluster and a per-screen primary action
 * are specified there but still have no counterpart -- no presence tracking and
 * no "primary action" concept exists -- so they are omitted rather than shipped
 * as dead controls.
 */
export function TopBar() {
  const { title, meta } = usePageHeader();

  return (
    <header
      className="sticky top-0 z-30 flex items-center border-b"
      style={{
        height: "var(--ob-topbar-height)",
        padding: "0 var(--ob-space-18)",
        gap: "var(--ob-space-12)",
        borderColor: "var(--ob-line)",
        background: "var(--ob-canvas)",
      }}
    >
      {title && (
        <h1
          className="truncate min-w-0 uppercase"
          style={{
            font: `var(--ob-type-breadcrumb-size)/var(--ob-type-breadcrumb-line) var(--ob-font-family-data)`,
            letterSpacing: "var(--ob-type-breadcrumb-tracking)",
            color: "var(--ob-text-subtle)",
            margin: 0,
          }}
        >
          {title}
        </h1>
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
      <InboxButton />
    </header>
  );
}
