import type { EscalationNote } from "@/lib/api/sla";
import { t } from "@/lib/i18n";

// A fixed locale and UTC: the value is a calendar date, so it must not shift with the viewer's zone.
const DAY_MONTH = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });

function dayMonth(note: EscalationNote): string {
  const raw = note.escalatedAt ?? note.dueDate;
  if (!raw) return "";
  const d = new Date(raw.length === 10 ? `${raw}T00:00:00Z` : raw);
  return Number.isNaN(d.getTime()) ? "" : DAY_MONTH.format(d);
}

/**
 * One escalation-history line (DOMAIN_RULES L182-184), e.g.
 * `Escalated to Sam Lee (Priya Shah's manager) on 21 Aug (automatic, day 1 overdue)`.
 * Every fragment is a t() template; nothing is concatenated here.
 */
export function formatEscalationNote(note: EscalationNote): string {
  const date = dayMonth(note);
  const suffix = t("sla.note.suffix", { days: String(note.overdueDays ?? 0) });
  if (note.route === "ADMINISTRATORS") {
    return `${t("sla.note.admins", { date })} ${suffix}`;
  }
  const name = note.escalatedToName;
  if (!name) return `${t("sla.note.unnamed", { date })} ${suffix}`;
  let who = name;
  if (note.route === "DEPARTMENT_HEAD") {
    who = t("sla.note.departmentHead", { name });
  } else if (note.route === "MANAGER" && note.latePersonName) {
    who = t("sla.note.manager", { name, late: note.latePersonName });
  }
  return `${t("sla.note.to", { who, date })} ${suffix}`;
}
