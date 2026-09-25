import { StatusPill, type StatusRole } from "@/components/ui/StatusPill";
import type { DocumentVisibilityTier } from "@/lib/api/documents";
import { t } from "@/lib/i18n";

/**
 * The `docs` table's Visibility column (`SCREENS.md` §7): "the visibility cell
 * stacks a Chip over a mono 8.5px scope label."
 *
 * **Ruling 1 (see `task-31-brief.md`).** `SCREENS.md` names four possible scope
 * labels -- `COMPANY-SHARED` / `CONTACT ONLY` / `EXPLICIT SHARE` / `REQUEST
 * OPEN` -- but `DocumentView` (`generated.ts`) carries only `visibilityTier`
 * (`COMPANY_SHARED | CONTACT_ONLY | SENSITIVE`): no field says WHY the viewer
 * can see the row (a department/team scope vs. an explicit share), and there
 * is no per-document open-request field at all. This component therefore
 * renders only the three labels the data actually supports, mapped straight
 * from `visibilityTier`. Fabricating `EXPLICIT SHARE` or `REQUEST OPEN` would
 * be exactly the unsupported assertion `StatusPill`'s own doc comment already
 * refuses to make for a defaulted status.
 *
 * **Ruling 2.** Nothing in the design docs names a semantic colour for these
 * three specific labels, so one is decided here deliberately, following the
 * same breadth-of-restriction logic `COMPONENTS.md` §11's role-scope table
 * already establishes (broader/looser access reads calmer, tighter
 * restriction reads more alert): `COMPANY_SHARED` -> `neutral` (the
 * unremarkable default -- everyone at the case team and the customer's
 * approved contacts can see it), `CONTACT_ONLY` -> `warn` (a real, active
 * restriction -- "other contacts at the same company cannot see it",
 * `DOMAIN_RULES.md` Q9), `SENSITIVE` -> `risk` (most consequential if
 * mishandled -- reinforced by, never replacing, the separately-specified lock
 * glyph on the filename itself).
 *
 * The Chip (`StatusPill`, the codebase's own name for "Chip" -- see
 * `COMPONENTS.md` §3 vs. `ui/StatusPill.tsx`) and the mono label beneath it
 * are two renderings of the same one field: the Chip shows the humanised
 * prose word (`StatusPill`'s own `humanise`), the mono label repeats it in
 * the exact upper-case, dash-joined text `SCREENS.md` names. Neither is a
 * second, independent signal -- there is only one field to render.
 */
const VISIBILITY_ROLE: Record<DocumentVisibilityTier, StatusRole> = {
  COMPANY_SHARED: "neutral",
  CONTACT_ONLY: "warn",
  SENSITIVE: "risk",
};

const SCOPE_LABEL_KEY: Record<DocumentVisibilityTier, string> = {
  COMPANY_SHARED: "documents.visibility.COMPANY_SHARED",
  CONTACT_ONLY: "documents.visibility.CONTACT_ONLY",
  SENSITIVE: "documents.visibility.SENSITIVE",
};

/** Mono 8.5px scope label -- `SCREENS.md`'s own size names no existing token exactly, so this reuses the nearest defined one (`--ob-type-mono-label-sm-*`, 9.5px), the same "closest token, not a new one-off size" choice `DataTable`'s own header cells already make. */
const SCOPE_LABEL_TEXT = {
  font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
  letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
  textTransform: "uppercase" as const,
};

export function VisibilityCell({ tier }: { tier?: DocumentVisibilityTier }) {
  if (!tier) {
    return <StatusPill status={undefined} />;
  }

  return (
    <div className="flex flex-col items-start" style={{ gap: "var(--ob-space-4)" }}>
      <StatusPill status={tier} role={VISIBILITY_ROLE[tier]} />
      <span className="text-text-subtle" style={SCOPE_LABEL_TEXT}>
        {t(SCOPE_LABEL_KEY[tier])}
      </span>
    </div>
  );
}
