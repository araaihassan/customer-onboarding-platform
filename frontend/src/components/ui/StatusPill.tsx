/**
 * Status pill (component-specs §6).
 *
 * The pill always contains the WORD. Never a bare coloured dot where a pill would
 * fit — colour is never the only signal, and review finding 10 flags three places
 * in the prototype that still break that rule. Do not add a fourth.
 *
 * Every status in the system maps to one of five roles. A status with no mapping
 * falls back to neutral rather than going uncoloured, so an unmapped value is
 * legible rather than invisible.
 */
export type StatusRole = "on-track" | "progress" | "at-risk" | "blocked" | "neutral";

const ROLE_BY_STATUS: Record<string, StatusRole> = {
  // customer
  ACTIVE: "on-track",
  PROSPECT: "neutral",
  ON_HOLD: "at-risk",
  INACTIVE: "blocked",
  // user / contact
  INVITED: "neutral",
  SUSPENDED: "at-risk",
  DEACTIVATED: "blocked",
};

export function roleForStatus(status: string): StatusRole {
  return ROLE_BY_STATUS[status] ?? "neutral";
}

/** Machine values are shown as words, so the label is humanised rather than raw. */
function humanise(status: string): string {
  return status.charAt(0) + status.slice(1).toLowerCase().replace(/_/g, " ");
}

export function StatusPill({ status, role }: { status: string; role?: StatusRole }) {
  const resolved = role ?? roleForStatus(status);

  return (
    <span
      style={{
        background: `var(--ob-status-${resolved}-bg)`,
        color: `var(--ob-status-${resolved}-fg)`,
        borderRadius: "var(--ob-radius-pill)",
        padding: "3px 8px",
        whiteSpace: "nowrap",
        textTransform: "uppercase",
        letterSpacing: "0.05em",
        font: "var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
      }}
    >
      {humanise(status)}
    </span>
  );
}
