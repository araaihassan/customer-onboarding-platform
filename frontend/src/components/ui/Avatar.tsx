/**
 * An initials avatar, and the one place the company/person distinction lives.
 *
 * Not in Task 27's file list, and added deliberately. component-specs §16 makes
 * the shape carry meaning — **rounded-square is a company, circular is a
 * person** — and that is exactly the kind of decision that erodes when four
 * screens each write their own div with a border-radius. Keeping the shape
 * behind a required `kind` prop means the distinction cannot be flattened by
 * accident, only by editing this file.
 *
 * The mark is neutral, never tinted. Colour in this system always means status,
 * and an avatar has no status to report — its subject's status is already a pill
 * beside it.
 *
 * Decorative by construction: the name is always rendered next to it, so
 * announcing the initials again would be noise.
 */
export function Avatar({
  name,
  kind,
  size = 30,
}: {
  name: string;
  kind: "company" | "person";
  /** 30px in tables and lists, 46px in the record header (tokens: avatar-size). */
  size?: number;
}) {
  const company = kind === "company";

  return (
    <span
      aria-hidden="true"
      className="grid shrink-0 place-items-center bg-bg-inset-strong text-text-secondary"
      style={{
        width: size,
        height: size,
        // radius-chip at 30px, radius-avatar-lg above it: an 8px corner on a
        // 46px square reads as a plain box rather than a rounded one.
        borderRadius: company
          ? size >= 40
            ? "var(--ob-radius-avatar-lg)"
            : "var(--ob-radius-chip)"
          : "var(--ob-radius-full)",
        font: `600 ${Math.round(size * 0.37)}px/1 var(--ob-font-family-ui)`,
      }}
    >
      {initials(name)}
    </span>
  );
}

/** Up to two initials; falls back to the first character of whatever we have. */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const first = parts[0];
  const last = parts[parts.length - 1];
  if (!first || !last) return "";
  return (first.slice(0, 1) + (parts.length > 1 ? last.slice(0, 1) : "")).toUpperCase();
}
