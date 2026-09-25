import { t } from "@/lib/i18n";

/**
 * The `docs` screen's "08 VISIBLE · 61 HIDDEN BY SCOPE" line (`SCREENS.md`
 * §7: "this line is important; it tells the user their view is scoped rather
 * than empty"). A pure, presentational component -- it fetches nothing
 * itself; whatever composes it (a future `docs` page-assembly task, per this
 * task's own Ruling 5) passes `visible`/`hidden` straight from
 * `DocumentService.visibilitySummary`'s response
 * (`useDocumentVisibilitySummary`, `lib/api/documents.ts`).
 *
 * **This is the codebase's second deliberate aggregate-disclosure exception**
 * -- see `DocumentService.visibilitySummary`'s own javadoc (backend) for the
 * full safety argument behind why `hidden` is safe to render at all: it is a
 * bare count bounded to the SAME filter context the caller already sees the
 * `visible` half of, never a document's own id, name, or any other field.
 * This component itself makes no authorization decision -- it only renders
 * two numbers it is handed.
 *
 * **Never disappears at zero** (the brief's own Step 1): a scoped view that
 * happens to hide nothing looks exactly like an unscoped one unless the line
 * still says "00 HIDDEN BY SCOPE" -- hiding the line in the one case a user
 * most needs reassurance ("is this really everything, or is my view just
 * narrow?") would defeat the entire reason this line exists.
 *
 * Both numbers are zero-padded to at least 2 digits (`SCREENS.md`'s own
 * `08 VISIBLE · 61 HIDDEN BY SCOPE` example), the same `padStart(2, "0")`
 * convention `workflow/StageRow.tsx` already uses for its own mono ordinal.
 * Instrument Sans is for human-written text; this is a machine-generated
 * count, so it renders in the mono/data font (`--ob-type-mono-label-*`,
 * CLAUDE.md's second held design decision), matching `MigrationTable`'s own
 * plain (non-Chip) mono counter text (`SCREENS.md` §10's "right-aligned mono
 * `02 SELECTED`").
 */
export function HiddenCountLine({ visible, hidden }: { visible: number; hidden: number }) {
  return (
    <span
      className="text-text-subtle"
      style={{
        font: `500 var(--ob-type-mono-label-size)/var(--ob-type-mono-label-line) var(--ob-font-family-data)`,
        letterSpacing: "var(--ob-type-mono-label-tracking)",
        textTransform: "uppercase",
      }}
    >
      {t("documents.hiddenCount.line", { visible: pad(visible), hidden: pad(hidden) })}
    </span>
  );
}

function pad(n: number): string {
  return String(Math.max(0, n)).padStart(2, "0");
}
