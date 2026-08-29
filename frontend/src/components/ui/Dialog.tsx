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
  eyebrow,
  onClose,
  children,
  maxWidth = 460,
}: {
  title: string;
  /**
   * An optional line rendered above the title -- added for `ForceCompleteDialog`'s
   * §18 "PRIVILEGED ACTION · APPROVAL REQUIRED" eyebrow, the same way `maxWidth`
   * was added for that dialog's 520px width. Opt-in and `undefined` by default, so
   * every other caller's header is unaffected.
   */
  eyebrow?: ReactNode;
  onClose: () => void;
  children: ReactNode;
  maxWidth?: number;
}) {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  // Captured on mount rather than on close: by the time the dialog unmounts the
  // element that opened it may already have been re-rendered away.
  const openerRef = useRef<HTMLElement | null>(null);

  const focusables = useCallback(() => {
    const nodes = panelRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE);
    return nodes ? Array.from(nodes).filter(isVisible) : [];
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
      style={{ background: "var(--ob-scrim-modal)", padding: "var(--ob-space-20)" }}
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
        className="w-full"
        style={{
          maxWidth: `${maxWidth}px`,
          background: "var(--ob-surface)",
          borderRadius: "var(--ob-radius-13)",
          boxShadow: "var(--ob-shadow-modal)",
          padding: "var(--ob-space-20)",
        }}
      >
        {eyebrow}
        <h2
          id={titleId}
          className="text-ink"
          style={{
            font: "600 var(--ob-type-section-heading-size)/var(--ob-type-section-heading-line) var(--ob-font-family-ui)",
            letterSpacing: "var(--ob-type-section-heading-tracking)",
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

/**
 * Everything the platform makes focusable by default, plus anything given an
 * explicit tab stop. `summary`, `[contenteditable]` and the media controls are
 * here because they are focusable and a trap that omits them lets Tab escape.
 */
const FOCUSABLE = [
  "button:not([disabled])",
  "[href]",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "summary",
  '[contenteditable]:not([contenteditable="false"])',
  "audio[controls]",
  "video[controls]",
  '[tabindex]:not([tabindex="-1"])',
].join(", ");

/**
 * A hidden control is not focusable in a browser, but `querySelectorAll` still
 * matches it — so counting it makes it the trap's last stop, and Tab from the
 * last *visible* control appears to do nothing while focus sits somewhere the
 * user can neither see nor leave.
 *
 * `offsetParent` would be the cheap check, but it is always null in jsdom, which
 * would make every stop invisible under test. Walking the computed styles works
 * in both, and a dialog's subtree is small enough that the cost does not matter.
 */
function isVisible(element: HTMLElement): boolean {
  if (element.hidden) return false;

  for (let node: HTMLElement | null = element; node; node = node.parentElement) {
    const style = window.getComputedStyle(node);
    if (style.display === "none" || style.visibility === "hidden") return false;
  }
  return true;
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
