import { StatusPill } from "@/components/ui/StatusPill";
import type { SlaClock } from "@/lib/api/sla";
import { t } from "@/lib/i18n";
import { formatSlaClock } from "./formatSlaClock";

/**
 * The SLA state as a pill. `SlaChipTone` names are already `StatusRole`s, so the
 * tone passes straight through. The label is words plus a number, so colour is
 * never the only signal; StatusPill sets it in the data font.
 */
export function SlaChip({ clock, prefix = false }: { clock: SlaClock; prefix?: boolean }) {
  const { label, tone } = formatSlaClock(clock);
  const text = prefix ? `${t("sla.chip.prefix")} ${label}` : label;
  return (
    <span data-testid="sla-chip" style={{ display: "inline-flex" }}>
      <StatusPill status={text} role={tone} verbatim />
    </span>
  );
}
