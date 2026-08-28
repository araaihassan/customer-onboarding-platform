"use client";

import { createContext, useCallback, useContext, useState } from "react";
import type { ReactNode } from "react";

/**
 * Net-new primitive (COMPONENTS.md §19), context-based to match
 * PageHeaderProvider's existing pattern (components/shell/PageHeader.tsx)
 * rather than inventing a different state-sharing mechanism.
 *
 * Design spec §8: "the prototype fires a Toast immediately. In production,
 * show the toast on server confirmation and roll back visibly on failure --
 * these are audited actions." This primitive only renders on demand; *when*
 * show() is called (mutation success, not click) is each caller's own
 * responsibility, not this component's.
 */
type ToastContextValue = { show: (message: string) => void };
const ToastContext = createContext<ToastContextValue | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState<string | null>(null);

  const show = useCallback((next: string) => {
    setMessage(next);
    window.setTimeout(() => setMessage(null), 2600);
  }, []);

  return (
    <ToastContext.Provider value={{ show }}>
      {children}
      {message && (
        <div
          role="status"
          aria-live="polite"
          style={{
            position: "fixed",
            bottom: 20,
            left: "50%",
            transform: "translateX(-50%)",
            background: "var(--ob-ink)",
            color: "var(--ob-canvas)",
            borderRadius: "var(--ob-radius-9)",
            padding: "10px 15px",
            font: "12.5px/1.3 var(--ob-font-family-ui)",
            boxShadow: "var(--ob-shadow-toast)",
            display: "flex",
            alignItems: "center",
            gap: "var(--ob-space-8)",
            animation: `om-pop var(--ob-duration-pop) var(--ob-ease-default)`,
          }}
        >
          {/* #5fd0a8 is a sanctioned literal -- COMPONENTS.md §19 specifies this
              exact hex for the toast's leading status dot with no --ob-* token
              backing it anywhere in tokens.css. Do not invent one. */}
          <span aria-hidden="true" style={{ width: 6, height: 6, borderRadius: "50%", background: "#5fd0a8" }} />
          {message}
        </div>
      )}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) throw new Error("useToast must be used within a ToastProvider");
  return context;
}
