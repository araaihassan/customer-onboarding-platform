import type { ReactNode } from "react";
import { t } from "@/lib/i18n";

/** The mono eyebrow every war-room dialog opens with: a plain neutral label, no status colour. */
export function SlaEyebrow() {
  return (
    <p
      style={{
        font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
        letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
        textTransform: "uppercase",
        color: "var(--ob-text-subtle)",
        marginBottom: "var(--ob-space-8)",
      }}
    >
      {t("sla.dialog.eyebrow")}
    </p>
  );
}

/** An inline failure: `role="alert"` so it is announced, risk-toned text carrying the server's own words. */
export function InlineError({ children }: { children: ReactNode }) {
  return (
    <p role="alert" style={{ color: "var(--ob-risk-fg)", font: "12px/1.4 var(--ob-font-family-ui)", marginTop: "var(--ob-space-6)" }}>
      {children}
    </p>
  );
}
