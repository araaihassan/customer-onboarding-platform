import type { SlaClock } from "@/lib/api/sla";
import { t } from "@/lib/i18n";

export type SlaChipTone = "risk" | "warn" | "info" | "ok";

/**
 * One decimal, truncated toward zero: a chip never claims more time than there is.
 * The small epsilon keeps float noise (1.9 arriving as 1.8999999) from truncating a digit early.
 */
export function days(value: number | undefined): string {
  const v = Number.isFinite(value) ? Math.max(0, value as number) : 0;
  return (Math.trunc(v * 10 + 1e-9) / 10).toFixed(1);
}

/** The one formatter for every SLA chip (STATE_AND_DATA L138). The server computed the numbers; this only words them. */
export function formatSlaClock(clock: SlaClock): { label: string; tone: SlaChipTone } {
  switch (clock.state) {
    case "PAUSED": // wins over dueToday/atRisk: a paused clock is not running down
      return { label: t("sla.chip.paused", { days: days(clock.pausedDays) }), tone: "info" };
    case "BREACHED":
      return {
        label: t("sla.chip.breached", { days: days((clock.elapsedDays ?? 0) - (clock.targetDays ?? 0)) }),
        tone: "risk",
      };
    case "MET":
      return { label: t("sla.chip.met"), tone: "ok" };
    default:
      if (clock.dueToday) return { label: t("sla.chip.dueToday"), tone: "warn" };
      return {
        label: t("sla.chip.left", { days: days(clock.remainingDays) }),
        tone: clock.atRisk ? "warn" : "ok",
      };
  }
}
