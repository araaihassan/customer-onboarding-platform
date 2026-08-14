"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import { ThemeToggle } from "./ThemeToggle";
import { usePageHeader } from "./PageHeader";
import { useAuth } from "@/lib/auth/useAuth";
import { t } from "@/lib/i18n";

/**
 * The shell header (component-specs §2).
 *
 * Search and notifications are specified there but are visual-only in the
 * prototype, so they are deliberately absent: a control that looks live and does
 * nothing costs more trust than a missing one.
 */
export function TopBar() {
  const { title, meta } = usePageHeader();

  return (
    <header
      className="sticky top-0 z-30 flex items-center border-b border-border-default bg-[var(--ob-bg-header)] px-[var(--ob-space-16)] md:px-[var(--ob-space-28)]"
      style={{
        height: "var(--ob-header-height)",
        gap: "var(--ob-space-18)",
        backdropFilter: "blur(10px)",
      }}
    >
      <div className="flex items-baseline min-w-0" style={{ gap: "var(--ob-space-9)" }}>
        <h1
          className="whitespace-nowrap text-text-primary"
          style={{
            font: "600 var(--ob-type-17-size)/var(--ob-type-17-line) var(--ob-font-family-ui)",
            letterSpacing: "var(--ob-type-17-tracking)",
          }}
        >
          {title}
        </h1>
        {/* Counts, dates and IDs — machine-generated, so mono. It ellipsises
            rather than wrapping; a second line here would move the 60px header. */}
        {meta && (
          <span
            className="overflow-hidden text-ellipsis whitespace-nowrap text-text-muted"
            style={{ font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)" }}
          >
            {meta}
          </span>
        )}
      </div>

      <div className="flex-1" />

      <ThemeToggle />
      <AccountMenu />
    </header>
  );
}

function AccountMenu() {
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const firstItemRef = useRef<HTMLButtonElement>(null);

  const close = useCallback((returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) triggerRef.current?.focus();
  }, []);

  useEffect(() => {
    if (!open) return;

    // Focus moves in on open so a keyboard user is not stranded behind the
    // trigger, and Escape puts it back where it came from.
    firstItemRef.current?.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") close(true);
    }
    function onPointerDown(event: MouseEvent) {
      if (!containerRef.current?.contains(event.target as Node)) close(false);
    }

    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("mousedown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("mousedown", onPointerDown);
    };
  }, [open, close]);

  const name = user?.fullName ?? user?.email ?? "";

  return (
    <div className="relative" ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={t("shell.account.open", { name })}
        onClick={() => setOpen((wasOpen) => !wasOpen)}
        className="flex items-center border border-border-default bg-bg-surface text-text-secondary"
        style={{
          height: "var(--ob-control-height)",
          borderRadius: "var(--ob-radius-control)",
          padding: "0 var(--ob-space-10)",
          gap: "var(--ob-space-8)",
          font: "500 var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)",
          cursor: "pointer",
        }}
      >
        {/* Neutral, not tinted. An avatar colour would be decoration, and colour
            in this system only ever means status. */}
        <span
          aria-hidden="true"
          className="grid place-items-center bg-bg-inset-strong text-text-secondary"
          style={{
            width: 22,
            height: 22,
            borderRadius: "var(--ob-radius-full)",
            font: "600 var(--ob-type-10-size)/1 var(--ob-font-family-ui)",
          }}
        >
          {initials(name)}
        </span>
        <span className="hidden md:inline whitespace-nowrap">{name}</span>
      </button>

      {open && (
        <div
          id={menuId}
          role="menu"
          aria-label={t("shell.account.open", { name })}
          className="absolute right-0 border border-border-default bg-bg-overlay"
          style={{
            top: "calc(100% + var(--ob-space-6))",
            minWidth: 200,
            borderRadius: "var(--ob-radius-overlay)",
            boxShadow: "var(--ob-elevation-popover)",
            padding: "var(--ob-space-8)",
          }}
        >
          <div style={{ padding: "var(--ob-space-6) var(--ob-space-8) var(--ob-space-10)" }}>
            <p
              className="text-text-primary"
              style={{ font: "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}
            >
              {user?.fullName}
            </p>
            {/* An address is a machine-readable identifier, so it is mono. */}
            <p
              className="text-text-muted"
              style={{ font: "var(--ob-type-10-5-size)/var(--ob-type-10-5-line) var(--ob-font-family-data)" }}
            >
              {user?.email}
            </p>
          </div>

          <button
            ref={firstItemRef}
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              // AuthGuard sees the cleared user and redirects to login, so this
              // deliberately does not route itself — one redirect, one owner.
              void logout();
            }}
            className="w-full text-left text-text-secondary hover:bg-bg-surface-subtle"
            style={{
              padding: "var(--ob-space-8)",
              borderRadius: "var(--ob-radius-control)",
              font: "var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)",
              cursor: "pointer",
            }}
          >
            {t("auth.signOut")}
          </button>
        </div>
      )}
    </div>
  );
}

/** Up to two initials; falls back to the first character of whatever we have. */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const first = parts[0];
  const last = parts[parts.length - 1];
  if (!first || !last) return "";
  return (first.slice(0, 1) + (parts.length > 1 ? last.slice(0, 1) : "")).toUpperCase();
}
