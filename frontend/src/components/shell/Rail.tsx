"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import type { FocusEvent } from "react";
import { initials } from "@/components/ui/Avatar";
import { useAuth } from "@/lib/auth/useAuth";
import { t } from "@/lib/i18n";

/**
 * The 56px icon rail (COMPONENTS.md §1). Brand mark at top, account menu at the
 * bottom -- no section buttons (Workspace/Configure/Customer portal), since this
 * app has no customer portal and no navigational split those buttons would
 * switch between yet; no ⌘K button, since there is no command palette to open.
 * Both are real gaps in the *design*, not oversights here -- see the refactor's
 * design spec §2 for why they're out of scope.
 */
export function Rail({ onToggleSidebar }: { onToggleSidebar?: () => void }) {
  return (
    // `relative z-[60]` puts the rail above Sidebar's `z-50` and its `z-40`
    // scrim. Without it the rail sits in normal flow with no stacking context,
    // and the account popover -- absolutely positioned and opening `left-full`,
    // i.e. straight into the sidebar's space -- is painted over by the sidebar
    // and cannot be seen at all. The rail is the outermost chrome; nothing
    // should ever cover it.
    <div
      className="relative z-[60] flex flex-col items-center bg-ink shrink-0"
      style={{ width: "var(--ob-rail-width)", padding: "14px 0 12px", gap: "var(--ob-space-6)" }}
    >
      <BrandMark />
      {onToggleSidebar && (
        <button
          type="button"
          onClick={onToggleSidebar}
          aria-label={t("shell.sidebar.toggle")}
          // Only below `lg`, the one breakpoint where the sidebar is a drawer
          // that needs opening. At 1024px and above the sidebar is already
          // inline, so this button had nothing to reveal -- it just painted the
          // drawer's scrim over the screen and appeared to do nothing.
          className="text-line-faint lg:hidden"
          style={{
            width: 34,
            height: 34,
            borderRadius: "var(--ob-radius-9)",
            fontSize: 14,
            display: "grid",
            placeItems: "center",
          }}
        >
          ☰
        </button>
      )}
      <div className="flex-1" />
      <AccountMenu />
    </div>
  );
}

function BrandMark() {
  return (
    <svg width="30" height="30" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="9" fill="var(--ob-accent-fg)" />
      <rect x="10.5" y="10.5" width="11" height="11" fill="var(--ob-canvas)" transform="rotate(45 16 16)" />
    </svg>
  );
}

/** Moved verbatim from TopBar.tsx's AccountMenu -- same open/close, focus and keyboard logic. */
function AccountMenu() {
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const firstActionRef = useRef<HTMLButtonElement>(null);

  const close = useCallback((returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) triggerRef.current?.focus();
  }, []);

  useEffect(() => {
    if (!open) return;
    firstActionRef.current?.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") close(true);
    }
    function onPointerDown(event: MouseEvent) {
      if (containerRef.current?.contains(event.target as Node)) return;
      close(containerRef.current?.contains(document.activeElement) ?? false);
    }

    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("mousedown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("mousedown", onPointerDown);
    };
  }, [open, close]);

  function onBlurWithin(event: FocusEvent<HTMLDivElement>) {
    if (!open) return;
    const next = event.relatedTarget as Node | null;
    if (next && !containerRef.current?.contains(next)) close(false);
  }

  const name = user?.fullName ?? user?.email ?? "";

  return (
    <div className="relative" ref={containerRef} onBlur={onBlurWithin}>
      <button
        ref={triggerRef}
        type="button"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={t("shell.account.open", { name })}
        onClick={() => setOpen((wasOpen) => !wasOpen)}
        className="grid place-items-center"
        style={{
          width: 30,
          height: 30,
          borderRadius: "var(--ob-radius-full)",
          background: "var(--ob-avatar-neutral)",
          color: "var(--ob-canvas)",
          font: "600 13px/1 var(--ob-font-family-ui)",
        }}
      >
        {initials(name)}
      </button>

      {open && (
        <div
          id={panelId}
          role="group"
          aria-label={t("shell.account.open", { name })}
          className="absolute bottom-0 left-full"
          style={{
            marginLeft: "var(--ob-space-8)",
            minWidth: 200,
            background: "var(--ob-surface)",
            border: "1px solid var(--ob-line)",
            borderRadius: "var(--ob-radius-10)",
            boxShadow: "var(--ob-shadow-dropdown)",
            padding: "var(--ob-space-8)",
          }}
        >
          <div style={{ padding: "var(--ob-space-6) var(--ob-space-8) var(--ob-space-10)" }}>
            <p style={{ font: "500 12.5px/1.3 var(--ob-font-family-ui)", color: "var(--ob-ink)" }}>
              {user?.fullName}
            </p>
            <p style={{ font: "10.5px/1.3 var(--ob-font-family-data)", color: "var(--ob-text-muted)" }}>
              {user?.email}
            </p>
          </div>
          <button
            ref={firstActionRef}
            type="button"
            onClick={() => {
              setOpen(false);
              void logout();
            }}
            className="w-full text-left hover:bg-surface-sunken"
            style={{
              padding: "var(--ob-space-8)",
              borderRadius: "var(--ob-radius-8)",
              font: "13px/1.4 var(--ob-font-family-ui)",
              color: "var(--ob-ink)",
            }}
          >
            {t("auth.signOut")}
          </button>
        </div>
      )}
    </div>
  );
}
