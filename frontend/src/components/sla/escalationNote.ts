import type { EscalationNote } from "@/lib/api/sla";
import { t } from "@/lib/i18n";

// A fixed table, not Intl: ICU 72+ renders September as "Sept" in en-GB, and the design wants "21 Sep".
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** `d MMM` read in UTC: the value is a calendar date, so it must not shift with the viewer's zone. */
export function dayMonthOf(raw: string | undefined): string {
  if (!raw) return "";
  const d = new Date(raw.length === 10 ? `${raw}T00:00:00Z` : raw);
  return Number.isNaN(d.getTime()) ? "" : `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
}

function dayMonth(note: EscalationNote): string {
  return dayMonthOf(note.escalatedAt ?? note.dueDate);
}

/**
 * One escalation-history line (DOMAIN_RULES L182-184), e.g.
 * `Escalated to Sam Lee (Priya Shah's manager) on 21 Aug (automatic, day 1 overdue)`.
 * Each whole sentence is one t() template, so wording and word order live in en.json.
 */
export function formatEscalationNote(note: EscalationNote): string {
  const date = dayMonth(note);
  const days = String(note.overdueDays ?? 0);
  if (note.route === "ADMINISTRATORS") return t("sla.note.admins", { date, days });
  const name = note.escalatedToName;
  if (!name) return t("sla.note.unnamed", { date, days });
  let who = name;
  if (note.route === "DEPARTMENT_HEAD") {
    who = t("sla.note.departmentHead", { name });
  } else if (note.route === "MANAGER" && note.latePersonName) {
    who = t("sla.note.manager", { name, late: note.latePersonName });
  }
  return t("sla.note.to", { who, date, days });
}
