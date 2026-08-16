"use client";

import { createContext, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";

/**
 * How a page tells the shell header what it is.
 *
 * Not in the task brief's file list, and added deliberately. The header's left
 * side is a screen title plus a meta line (component-specs §2), but the header
 * lives in the layout and the title belongs to the page — App Router gives the
 * layout no way to read it. Without a channel between them the title would have
 * to be derived from the route, which cannot express "Northwind Foods" or
 * "48 active · 6 at risk", and every later sub-project would work around it.
 * One small context now is much cheaper than nine workarounds later.
 *
 * The meta line is optional because it is machine-generated (counts, dates, IDs)
 * and many screens have nothing honest to put there.
 */
type PageHeaderState = { title: string; meta?: string };

type PageHeaderContextValue = PageHeaderState & {
  setPageHeader: (next: PageHeaderState) => void;
};

const PageHeaderContext = createContext<PageHeaderContextValue | null>(null);

export function PageHeaderProvider({ children }: { children: ReactNode }) {
  const [state, setPageHeader] = useState<PageHeaderState>({ title: "" });

  const value = useMemo<PageHeaderContextValue>(
    () => ({ ...state, setPageHeader }),
    [state],
  );

  return <PageHeaderContext.Provider value={value}>{children}</PageHeaderContext.Provider>;
}

/** Read side, for the header itself. */
export function usePageHeader(): PageHeaderState {
  const context = useContext(PageHeaderContext);
  return context ? { title: context.title, meta: context.meta } : { title: "" };
}

/**
 * Write side, for pages. Called during render, applied in an effect so the
 * parent header is not updated while the child is still rendering.
 *
 * Outside a provider this is a no-op rather than a throw: a page rendered on its
 * own — in a test, or in a future embedded view — should still render, and a
 * missing title is visible in the interface rather than fatal to it.
 *
 * The cleanup is not optional. This state lives in the provider ABOVE the router
 * outlet, so it survives navigation: without a reset on unmount, moving from a
 * page that sets a title to one that does not — a page that forgot the hook, or
 * an error or loading branch that never reaches it — would leave the header
 * announcing the previous screen's title over the new one. A stale-but-plausible
 * title is worse than an absent one, because nothing about it looks wrong.
 */
export function useSetPageHeader(title: string, meta?: string): void {
  const context = useContext(PageHeaderContext);
  const setPageHeader = context?.setPageHeader;

  useEffect(() => {
    setPageHeader?.({ title, meta });
    return () => setPageHeader?.({ title: "" });
  }, [setPageHeader, title, meta]);
}
