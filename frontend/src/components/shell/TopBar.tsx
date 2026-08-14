"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import type { FocusEvent } from "react";
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
        {/* Rendered only when a page has supplied one. An <h1> with no text is an
            axe `empty-heading` violation, and it would be present on the first
            paint of every screen — the title arrives from the page's effect,
            which runs after this header has already rendered.

            `truncate min-w-0` because a record title is arbitrary length: with
            nowrap alone the h1's min-content width is the whole string, so a long
            customer name plus the two right-hand controls overflows the 60px
            header at narrow widths instead of ellipsising. */}
        {title && (
          <h1
            className="truncate min-w-0 text-text-primary"
            style={{
              font: "600 var(--ob-type-17-size)/var(--ob-type-17-line) var(--ob-font-family-ui)",
              letterSpacing: "var(--ob-type-17-tracking)",
            }}
          >
            {title}
          </h1>
        )}
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

/**
 * The account control: a disclosure button and a labelled popover, deliberately
 * NOT the ARIA menu pattern.
 *
 * `role="menu"` would promise arrow-key navigation, type-ahead and a roving
 * tabindex that a single action does not need and this does not implement — and
 * it only admits menuitem/group/separator children, so the identity block inside
 * would be an `aria-required-children` violation that several screen readers
 * respond to by announcing nothing at all. A plain popover with a real <button>
 * gets the same behaviour from the platform for free.
 */
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

    // Focus moves in on open so a keyboard user is not stranded behind the
    // trigger, and Escape puts it back where it came from.
    firstActionRef.current?.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") close(true);
    }
    function onPointerDown(event: MouseEvent) {
      if (containerRef.current?.contains(event.target as Node)) return;
      // Return focus only if it was inside the popover we are about to unmount;
      // otherwise focus would land on <body> and the user would lose their place.
      // If they clicked something focusable, the click moves focus there anyway.
      close(containerRef.current?.contains(document.activeElement) ?? false);
    }

    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("mousedown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("mousedown", onPointerDown);
    };
  }, [open, close]);

  /**
   * Tab out closes it. Without this the popover stays open behind the user's
   * focus, floating over content they are now interacting with. relatedTarget is
   * null when focus fell to <body>; the pointer handler above owns that case, so
   * this only acts on a deliberate move to another element.
   */
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

      {/* role="group" rather than a bare div: aria-label is prohibited on a
          generic element and axe flags it, but group takes a label and imposes no
          required children. */}
      {open && (
        <div
          id={panelId}
          role="group"
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
            ref={firstActionRef}
            type="button"
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
