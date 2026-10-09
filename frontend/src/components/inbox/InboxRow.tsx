"use client";

import { t } from "@/lib/i18n";
import { formatRelative } from "@/lib/format/date";
import type { NotificationItem } from "@/lib/api/notifications";
import { TONE_ROLE, iconFor } from "./notificationVisuals";

/** One notification. Unread is the white surface plus an "Unread:" accessible name -- no dot, no colour-only cue. */
export function InboxRow({ item, onOpen }: { item: NotificationItem; onOpen: (item: NotificationItem) => void }) {
  const role = TONE_ROLE[item.tone ?? "INFO"];
  const Icon = iconFor(item.type);
  const title = item.title ?? "";
  const unread = !item.read;
  return (
    <button
      type="button"
      onClick={() => onOpen(item)}
      data-unread={unread ? "true" : undefined}
      aria-label={unread ? t("inbox.unreadRow", { title }) : undefined}
      className="w-full text-left"
      style={{
        display: "grid",
        gridTemplateColumns: "26px 1fr",
        gap: 10,
        padding: "11px 16px",
        borderBottom: "1px solid var(--ob-line-soft)",
        background: unread ? "var(--ob-surface)" : "transparent",
      }}
    >
      <span
        role="img"
        aria-label={t(`inbox.tone.${role}`)}
        style={{
          width: 26,
          height: 26,
          borderRadius: "var(--ob-radius-7)",
          background: `var(--ob-${role}-bg)`,
          color: `var(--ob-${role}-fg)`,
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <Icon size={14} />
      </span>
      <span style={{ display: "flex", flexDirection: "column", gap: 2, minWidth: 0 }}>
        <span className="text-ink" style={{ font: "600 12.5px/1.35 var(--ob-font-family-ui)" }}>{title}</span>
        {item.body && (
          <span className="text-text-muted" style={{ font: "11.5px/1.4 var(--ob-font-family-ui)" }}>{item.body}</span>
        )}
        {item.createdAt && (
          <span
            className="text-text-faint"
            style={{ font: "9.5px/1.3 var(--ob-font-family-data)", textTransform: "uppercase" }}
          >
            {formatRelative(item.createdAt)}
          </span>
        )}
      </span>
    </button>
  );
}
