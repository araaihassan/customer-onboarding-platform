"use client";

import { useCallback, useEffect, useId, useRef } from "react";
import type { ReactNode } from "react";

/**
 * A modal dialog.
 *
 * Not in Task 27's file list, and added deliberately: the create form and the
 * deactivation confirmation are both modal, and focus management written twice
 * is focus management wrong once. The design has no dialog spec — the prototype
 * has no modals at all — so the surface follows §3's overlay treatment, which is
 * the one place elevation is legitimate: a dialog genuinely floats.
 *
 * Escape closes it, focus moves in on open and returns to the trigger on close,
 * and Tab is trapped inside — a dialog a keyboard user can tab out of while it
 * still covers the page is worse than no dialog.
 */
export function Dialog({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  // Captured on mount rather than on close: by the time the dialog unmounts the
  // element that opened it may already have been re-rendered away.
  const openerRef = useRef<HTMLElement | null>(null);

  const focusables = useCallback(() => {
    const nodes = panelRef.current?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])',
    );
    return nodes ? Array.from(nodes) : [];
  }, []);

  useEffect(() => {
    openerRef.current = document.activeElement as HTMLElement | null;
    focusables()[0]?.focus();

    const opener = openerRef.current;
    return () => opener?.focus();
  }, [focusables]);

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

      // Wrap at both ends. Without this, Tab from the last control walks into
      // the page behind the overlay, where nothing is visible and nothing the
      // user reaches is what they think they are on.
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

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      // The scrim ink is the same 25,26,30 the elevation tokens are built from,
      // so it stays consistent with every shadow in the system. The token set
      // has no scrim role because the design has no modals at all.
      style={{ background: "rgba(25, 26, 30, 0.42)", padding: "var(--ob-space-20)" }}
      // The backdrop is not the only way out — Escape and Cancel both work — so
      // it is deliberately not focusable and carries no role of its own.
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full bg-bg-overlay border border-border-default"
        style={{
          maxWidth: 460,
          borderRadius: "var(--ob-radius-overlay)",
          boxShadow: "var(--ob-elevation-popover)",
          padding: "var(--ob-space-20)",
        }}
      >
        <h2
          id={titleId}
          className="text-text-primary"
          style={{
            font: "600 var(--ob-type-15-size)/var(--ob-type-15-line) var(--ob-font-family-ui)",
            marginBottom: "var(--ob-space-16)",
          }}
        >
          {title}
        </h2>
        {children}
      </div>
    </div>
  );
}

/** The right-aligned action row every dialog ends with. */
export function DialogActions({ children }: { children: ReactNode }) {
  return (
    <div
      className="flex justify-end"
      style={{ gap: "var(--ob-space-8)", marginTop: "var(--ob-space-20)" }}
    >
      {children}
    </div>
  );
}
