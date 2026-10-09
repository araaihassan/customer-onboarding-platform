"use client";

import { useCallback, useRef, useState } from "react";
import { BellIcon } from "@/components/icons";
import { useUnreadCount } from "@/lib/api/notifications";
import { useAuth } from "@/lib/auth/useAuth";
import { t } from "@/lib/i18n";
import { InboxDrawer } from "./InboxDrawer";
import { useInboxShortcut } from "./useInboxShortcut";

/** The top bar's Inbox control. Internal users only: portal notifications are sub-project 7's. */
export function InboxButton() {
  const { user } = useAuth();
  const internal = user?.userType === "INTERNAL";
  const count = useUnreadCount(internal);
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const toggle = useCallback(() => setOpen((o) => !o), []);
  const close = useCallback(() => setOpen(false), []);
  useInboxShortcut(toggle, internal);
  if (!internal) return null;

  const n = count.data ?? 0;
  return (
    <>
      <button
        ref={trigger}
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-label={n > 0 ? t("inbox.buttonWithCount", { count: String(n) }) : t("inbox.title")}
        className="flex items-center rounded-9 border border-line bg-surface text-ink"
        style={{ height: "var(--ob-control-height)", padding: "0 10px", gap: "6px", font: "500 12.5px/1 var(--ob-font-family-ui)" }}
      >
        <BellIcon size={15} />
        <span>{t("inbox.title")}</span>
        {n > 0 && (
          // A count, not a status: the ink fill carries no colour meaning.
          <span
            className="bg-ink text-surface"
            style={{ font: "600 9.5px/1 var(--ob-font-family-data)", padding: "3px 5px", borderRadius: "var(--ob-radius-full)" }}
          >
            {n > 99 ? "99+" : n}
          </span>
        )}
      </button>
      {open && <InboxDrawer onClose={close} returnFocusTo={trigger} />}
    </>
  );
}
