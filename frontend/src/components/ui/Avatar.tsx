import type { CSSProperties } from "react";

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
 * DESIGN_TOKENS.md's avatar colour-cycle: the background is hashed from the
 * subject's name into a 7-colour palette, rather than a single neutral tone —
 * still never a status colour (an avatar has no status to report; that's a
 * pill beside it), just enough variety to tell rows apart at a glance.
 *
 * Decorative by construction: the name is always rendered next to it, so
 * announcing the initials again would be noise.
 */

const AVATAR_PALETTE = ["#10736b", "#b4392f", "#6a4fb0", "#2b5fb0", "#9a6410", "#2f7d4f", "#4b4842"];

function paletteColor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
  return AVATAR_PALETTE[hash % AVATAR_PALETTE.length]!;
}

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
  const style: CSSProperties = {
    width: size,
    height: size,
    borderRadius: kind === "person" ? "var(--ob-radius-full)" : "var(--ob-radius-5)",
    background: paletteColor(name),
    color: "var(--ob-canvas)",
    font: `600 ${Math.round(size * 0.37)}px/1 var(--ob-font-family-ui)`,
    display: "grid",
    placeItems: "center",
  };

  return (
    <span aria-hidden="true" className="shrink-0" style={style}>
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
