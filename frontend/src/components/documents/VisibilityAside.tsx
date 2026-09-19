import { humanise } from "@/components/ui/StatusPill";
import type { DocumentVisibilityTier } from "@/lib/api/documents";
import { t } from "@/lib/i18n";

const TIER_ORDER: DocumentVisibilityTier[] = ["COMPANY_SHARED", "CONTACT_ONLY", "SENSITIVE"];

/** Same semantic-fg mapping `VisibilityCell` already establishes for these three tiers -- one colour vocabulary for visibility across the app, not a second one invented here. */
const TIER_FG: Record<DocumentVisibilityTier, string> = {
  COMPANY_SHARED: "var(--ob-neutral-fg)",
  CONTACT_ONLY: "var(--ob-warn-fg)",
  SENSITIVE: "var(--ob-risk-fg)",
};

const TIER_EXPLANATION_KEY: Record<DocumentVisibilityTier, string> = {
  COMPANY_SHARED: "documents.aside.companyShared",
  CONTACT_ONLY: "documents.aside.contactOnly",
  SENSITIVE: "documents.aside.sensitive",
};

/**
 * `SCREENS.md` §7's "How visibility works" -- the `docs` screen's right
 * aside, "the three tiers explained in one sentence each, colour-coded by
 * their semantic fg." Standalone and reusable (Ruling 2, task-33-brief):
 * mounted inside `UploadDialog`, beside the visibility select, rather than
 * the case workspace's own right rail (`SCREENS.md` §3 -- CUSTOMER/OTHER
 * JOURNEYS/SLA CLOCK/PARTICIPANTS/ACTIONS -- which is reserved for later
 * sub-projects and has no Documents-specific slot). A future task assembling
 * the tenant-wide `docs` page's own right column reuses this component
 * unchanged.
 *
 * All three tiers' explanations are ALWAYS rendered -- this design system has
 * no hide/reveal interaction for this content, and hiding two-thirds of it
 * would be a new interaction pattern invented for this one screen, not a
 * faithful implementation. `emphasize` (the currently selected tier, when
 * known) visually distinguishes its own row via a real border/background
 * difference AND a bolder weight -- never colour alone (CLAUDE.md's fourth
 * held design decision) -- so a SENSITIVE selection surfaces its own
 * explanation as the prominent one rather than restricting anything
 * silently, which is exactly what the brief's own Step 1 test requires.
 */
export function VisibilityAside({ emphasize }: { emphasize?: DocumentVisibilityTier }) {
  return (
    <div
      className="flex flex-col"
      style={{
        gap: "var(--ob-space-8)",
        padding: "var(--ob-space-11)",
        border: "1px solid var(--ob-line)",
        borderRadius: "var(--ob-radius-10)",
        background: "var(--ob-surface-sunken)",
      }}
    >
      <h6
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
        }}
      >
        {t("documents.aside.title")}
      </h6>

      {TIER_ORDER.map((tier) => {
        const isEmphasized = tier === emphasize;
        return (
          <div
            key={tier}
            data-tier={tier}
            data-emphasized={isEmphasized ? "true" : undefined}
            className="flex flex-col"
            style={{
              gap: "var(--ob-space-4)",
              padding: "var(--ob-space-6) var(--ob-space-8)",
              borderRadius: "var(--ob-radius-7)",
              border: `1px solid ${isEmphasized ? TIER_FG[tier] : "transparent"}`,
              background: isEmphasized ? "var(--ob-surface)" : "transparent",
            }}
          >
            <span
              style={{
                color: TIER_FG[tier],
                fontWeight: isEmphasized ? 700 : 600,
                font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
              }}
            >
              {humanise(tier)}
            </span>
            <span
              className="text-text-subtle"
              style={{
                font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
              }}
            >
              {t(TIER_EXPLANATION_KEY[tier])}
            </span>
          </div>
        );
      })}
    </div>
  );
}
