"use client";

import { useEffect } from "react";

/**
 * Cmd/Ctrl-J toggles the inbox (README "Keyboard"). Never while the user is
 * typing. `enabled` lets a caller that must not offer the shortcut (a portal
 * user) attach no listener at all, instead of a no-op one.
 */
export function useInboxShortcut(toggle: () => void, enabled = true) {
  useEffect(() => {
    if (!enabled) return;
    function onKeyDown(event: KeyboardEvent) {
      if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== "j") return;
      const target = event.target as HTMLElement | null;
      if (target?.closest?.("input, textarea, select, [contenteditable='true']")) return;
      event.preventDefault();
      toggle();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [toggle, enabled]);
}
