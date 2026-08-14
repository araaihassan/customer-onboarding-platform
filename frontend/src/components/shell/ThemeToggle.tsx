"use client";

import { useEffect, useState } from "react";
import { useTheme } from "next-themes";
import { t } from "@/lib/i18n";

/**
 * Switches the theme next-themes writes as data-theme on <html>.
 *
 * The generated icon set has no sun or moon — it was drawn for a product with no
 * theme toggle — and hand-adding one would mean editing a generated file. So the
 * control is worded instead: it names the theme it will switch to, which also
 * satisfies the rule that colour is never the only signal.
 *
 * The mounted guard is not ceremony. next-themes reads the stored preference on
 * the client, so on the very first client render resolvedTheme can already be
 * "dark" while the server rendered "light" — a hydration mismatch on this
 * button's text. Holding the label at its server value until after mount removes
 * the mismatch rather than suppressing the warning about it.
 */
export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);

  const isDark = mounted && resolvedTheme === "dark";
  const target = isDark ? "light" : "dark";

  return (
    <button
      type="button"
      onClick={() => setTheme(target)}
      aria-label={isDark ? t("shell.theme.toLight") : t("shell.theme.toDark")}
      className="border border-border-default bg-bg-surface text-text-secondary"
      style={{
        height: "var(--ob-control-height)",
        borderRadius: "var(--ob-radius-control)",
        padding: "0 var(--ob-space-11)",
        font: "500 var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)",
        cursor: "pointer",
      }}
    >
      {isDark ? t("shell.theme.light") : t("shell.theme.dark")}
    </button>
  );
}
