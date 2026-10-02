import type { StatusRole } from "@/components/ui/StatusPill";
import type { AgreementDisplayStatus } from "@/lib/api/agreements";

export type StatusTone = "neutral" | "info" | "warn" | "success" | "danger";

/** Colour means status: each agreement state maps to one tone, always rendered with its word. */
export function statusTone(s: AgreementDisplayStatus): StatusTone {
  switch (s) {
    case "UNDER_REVIEW":
    case "APPROVED":
      return "info";
    case "SENT":
    case "AWAITING_SIGNATURE":
      return "warn";
    case "SIGNED":
      return "success";
    case "EXPIRED":
      return "danger";
    default:
      return "neutral";
  }
}

export function statusLabelKey(s: AgreementDisplayStatus): string {
  return `agreements.status.${s}`;
}

/** `StatusPill` names its roles ok/risk where the design vocabulary says success/danger. */
export function toneRole(tone: StatusTone): StatusRole {
  return tone === "success" ? "ok" : tone === "danger" ? "risk" : tone;
}
