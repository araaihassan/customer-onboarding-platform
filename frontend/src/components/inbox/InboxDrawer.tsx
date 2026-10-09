"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import type { RefObject } from "react";
import { createPortal } from "react-dom";
import { XIcon } from "@/components/icons";
import { Button } from "@/components/ui/Button";
import { FOCUSABLE, isVisible } from "@/components/ui/Dialog";
import { useMarkAllRead, useUnreadCount } from "@/lib/api/notifications";
import { t } from "@/lib/i18n";
import { InboxList } from "./InboxList";
import { PreferencesPane } from "./PreferencesPane";

/**
 * The right-hand inbox drawer (COMPONENTS §16). It genuinely floats over the
 * page, so its shadow is legitimate. Focus handling mirrors `Dialog`: focus in
 * on open, Tab trapped, Escape closes, focus back on the trigger on close.
 */
export function InboxDrawer({
  onClose,
  returnFocusTo,
}: {
  onClose: () => void;
  returnFocusTo: RefObject<HTMLElement | null>;
}) {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const [pane, setPane] = useState<"list" | "preferences">("list");
  const unread = useUnreadCount(true).data ?? 0;
  const markAllRead = useMarkAllRead();

  const focusables = useCallback(() => {
    const nodes = panelRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE);
    return nodes ? Array.from(nodes).filter(isVisible) : [];
  }, []);

  useEffect(() => {
    focusables()[0]?.focus();
    // The trigger is a stable, always-mounted button; read it now, as Dialog does.
    const trigger = returnFocusTo.current;
    return () => trigger?.focus();
  }, [focusables, returnFocusTo]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
        return;
      }
      if (event.key !== "Tab") return;
      const stops = focusables();
      const first = stops[0];
      const last = stops[stops.length - 1];
      if (!first || !last) return;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [focusables, onClose]);

  // Portalled to <body>: the top bar is sticky + z-30, which is its own stacking
  // context, so a drawer left inside it could never rise above the Rail (z-60).
  // Scrim z-70 and panel z-80 sit above the Rail, the mobile Sidebar (z-50) and
  // every Dialog (z-50); there is no shared --ob-z-* scale to extend.
  if (typeof document === "undefined") return null;
  return createPortal(
    <>
      <div
        data-inbox-scrim
        aria-hidden="true"
        className="fixed inset-0 z-[70]"
        style={{ background: "var(--ob-scrim-drawer)" }}
        onClick={onClose}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="fixed inset-y-0 right-0 z-[80] flex flex-col"
        style={{
          width: "min(390px, 100vw)",
          background: "var(--ob-canvas)",
          borderLeft: "1px solid var(--ob-line)",
          boxShadow: "var(--ob-shadow-drawer)",
          animation: "om-slide var(--ob-duration-slide) var(--ob-ease-default)",
        }}
      >
        <div
          className="flex items-center border-b"
          style={{ height: "var(--ob-topbar-height)", padding: "0 16px", gap: "10px", borderColor: "var(--ob-line)", flexShrink: 0 }}
        >
          <h2 id={titleId} className="text-ink" style={{ font: "600 14px/1 var(--ob-font-family-ui)", margin: 0 }}>
            {t("inbox.title")}
          </h2>
          <span className="text-text-faint" style={{ font: "500 9.5px/1 var(--ob-font-family-data)" }}>
            {t("inbox.unread", { count: String(unread) })}
          </span>
          <div className="flex-1" />
          {unread > 0 && (
            <Button type="button" variant="text-link" onClick={() => markAllRead.mutate()}>
              {t("inbox.markAllRead")}
            </Button>
          )}
          <Button
            type="button"
            variant="small-secondary"
            aria-pressed={pane === "preferences"}
            onClick={() => setPane((p) => (p === "list" ? "preferences" : "list"))}
          >
            {pane === "list" ? t("inbox.preferences") : t("inbox.back")}
          </Button>
          <button
            type="button"
            aria-label={t("common.close")}
            onClick={onClose}
            className="flex items-center justify-center text-text-subtle"
            style={{ width: "28px", height: "28px" }}
          >
            <XIcon size={14} />
          </button>
        </div>
        <div style={{ flex: 1, overflowY: "auto" }}>
          {pane === "list" ? <InboxList onNavigate={onClose} /> : <PreferencesPane />}
        </div>
      </div>
    </>,
    document.body,
  );
}
