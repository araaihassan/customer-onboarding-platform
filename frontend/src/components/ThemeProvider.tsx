"use client";

import { ThemeProvider as NextThemesProvider } from "next-themes";
import type { ReactNode } from "react";

/**
 * attribute="data-theme", NOT "class".
 *
 * tokens.css keys its dark theme on [data-theme="dark"]. With next-themes set to
 * "class" the dark tokens would never apply and the application would render
 * light-on-light in dark mode — the failure is silent, because every variable
 * still resolves, just to its light value.
 *
 * defaultTheme="system" is safe: tokens.css also carries a
 * prefers-color-scheme: dark block for the case where no attribute is set at all.
 *
 * disableTransitionOnChange stops every tokenised colour animating at once when
 * the theme flips.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  return (
    <NextThemesProvider
      attribute="data-theme"
      defaultTheme="system"
      enableSystem
      disableTransitionOnChange
    >
      {children}
    </NextThemesProvider>
  );
}
