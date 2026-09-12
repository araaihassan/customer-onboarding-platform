import Link from "next/link";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { EmptyState } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import { JourneyIcon } from "@/components/icons";
import type { StatusRole } from "@/components/ui/StatusPill";
import { shortId } from "@/lib/api/customers";
import type { ProgrammeJourney } from "@/lib/api/programmes";
import { t } from "@/lib/i18n";

/**
 * A journey's status colour, duplicated locally rather than folded into the shared
 * `StatusPill` map -- the same call `CaseSwitcher`'s own `dotColor` and
 * `MilestoneRow`'s own `ROLE_BY_STATUS` already made, and for the same reason:
 * `ProgrammeJourneyView.status` is `CaseStatus`, but a programme's journey list is not
 * the only place that enum is rendered, and unifying every caller is a separate
 * refactor from this screen. Kept identical to `CaseSwitcher.dotColor`'s mapping so the
 * same status reads the same colour everywhere a journey is shown.
 */
const ROLE_BY_STATUS: Record<string, StatusRole> = {
  ACTIVE: "accent",
  ON_HOLD: "warn",
  COMPLETED: "ok",
  CANCELLED: "risk",
};

/**
 * The journeys a programme contains -- design spec §8.1's journeys list, with
 * per-journey progress and status. Reads through `AuthorizedQuery` under `case.view`
 * on the server (§6.4), so what arrives here is already the filtered set;
 * `ProgrammeRollupBar` is what states the coverage in words, not this component.
 *
 * `slug`/`customerId` are optional so this renders standalone in a unit test with
 * no routing context -- when both are present each journey's name is a real link
 * into the case workspace (component-specs' own "row's primary cell holds a real
 * link" rule, `CustomerTable`'s same choice); otherwise the name renders as plain
 * text.
 */
export function ProgrammeJourneyList({
  journeys,
  slug,
  customerId,
}: {
  journeys: ProgrammeJourney[];
  slug?: string;
  customerId?: string;
}) {
  if (journeys.length === 0) {
    return (
      <EmptyState
        icon={<JourneyIcon size={24} />}
        title={t("programme.journeys.empty")}
        description={t("programme.journeys.emptyHint")}
      />
    );
  }

  return (
    <ul className="flex flex-col">
      {journeys.map((journey) => {
        const status = journey.status ?? "";
        const progress = journey.progressPercent ?? 0;
        const href = slug && customerId ? `/t/${slug}/customers/${customerId}/cases/${journey.caseId}` : undefined;

        return (
          <li
            key={journey.caseId}
            className="flex items-center border-t border-line-faint first:border-t-0"
            style={{ gap: "var(--ob-space-11)", padding: "var(--ob-space-11) 0" }}
          >
            <div className="flex-1 min-w-0">
              {href ? (
                <Link
                  href={href}
                  className="block truncate text-ink hover:underline"
                  style={{ font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
                >
                  {journey.name}
                </Link>
              ) : (
                <span
                  className="block truncate text-ink"
                  style={{ font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
                >
                  {journey.name}
                </span>
              )}
              <p
                className="truncate text-text-subtle"
                style={{ font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)" }}
              >
                {shortId(journey.caseId)}
              </p>
            </div>

            <div className="flex-1 min-w-0 max-w-[220px]">
              <ProgressBar value={progress} label={t("programme.journeys.progressFor", { name: journey.name ?? "" })} showPercentage />
            </div>

            {/* Colour is never the only signal (binding decision 4): the pill always
                carries the humanised status word alongside its role colour. */}
            <StatusPill status={t(`case.status.${status}`)} role={ROLE_BY_STATUS[status] ?? "neutral"} />
          </li>
        );
      })}
    </ul>
  );
}
