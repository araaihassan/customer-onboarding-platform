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
export type StatusRole = "ok" | "accent" | "warn" | "risk" | "neutral";

const ROLE_BY_STATUS: Record<string, StatusRole> = {
  // customer
  ACTIVE: "ok",
  PROSPECT: "neutral",
  ON_HOLD: "warn",
  INACTIVE: "risk",
  // user / contact
  INVITED: "neutral",
  SUSPENDED: "warn",
  DEACTIVATED: "risk",
};

export function roleForStatus(status: string): StatusRole {
  return ROLE_BY_STATUS[status] ?? "neutral";
}

/** Machine values are shown as words, so the label is humanised rather than raw. */
function humanise(status: string): string {
  return status.charAt(0) + status.slice(1).toLowerCase().replace(/_/g, " ");
}

/**
 * An absent status is rendered as a neutral em dash, never defaulted to a real
 * one.
 *
 * Every other absent field on a record falls back to an em dash and claims
 * nothing. A defaulted status would be different in kind: a coloured, worded
 * assertion about the record that no data supports — which is the "colour always
 * means status" rule rather than a style preference. Callers used to write
 * `status ?? "PROSPECT"`, and the point of accepting undefined here is that they
 * no longer have to reach for a default that is a guess.
 */
export function StatusPill({ status, role }: { status?: string; role?: StatusRole }) {
  if (!status) return <StatusPill status="—" role="neutral" />;

  const resolved = role ?? roleForStatus(status);

  return (
    <span
      style={{
        background: `var(--ob-${resolved}-bg)`,
        color: `var(--ob-${resolved}-fg)`,
        borderRadius: "var(--ob-radius-5)",
        padding: "3px 8px",
        whiteSpace: "nowrap",
        textTransform: "uppercase",
        letterSpacing: "0.05em",
        font: "var(--ob-type-mono-chip-size)/var(--ob-type-mono-chip-line) var(--ob-font-family-data)",
      }}
    >
      {humanise(status)}
    </span>
  );
}
