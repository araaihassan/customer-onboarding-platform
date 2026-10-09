import type { ComponentType } from "react";
import type { IconProps } from "@/components/icons";
import {
  AlertTriangleIcon,
  CheckCircleIcon,
  CheckIcon,
  ClockIcon,
  FileSignatureIcon,
  FileTextIcon,
  MessageSquareIcon,
  RefreshCwIcon,
  UserCheckIcon,
} from "@/components/icons";
import type { NotificationItem } from "@/lib/api/notifications";

/** Tone is the row's status (DESIGN_TOKENS semantic pairs); colour is always paired with the icon and a label. */
export const TONE_ROLE = { RISK: "risk", WARN: "warn", OK: "ok", INFO: "info" } as const;

const ICONS: Record<string, ComponentType<IconProps>> = {
  ESCALATION: AlertTriangleIcon,
  RISK_CHANGED: AlertTriangleIcon,
  TASK_ASSIGNED: CheckIcon,
  TASK_OVERDUE: ClockIcon,
  DEADLINE_APPROACHING: ClockIcon,
  EXPIRY_RENEWAL: ClockIcon,
  MILESTONE_COMPLETED: CheckCircleIcon,
  STAGE_CHANGED: RefreshCwIcon,
  WORKFLOW_PUBLISHED: RefreshCwIcon,
  DOCUMENT_REQUESTED: FileTextIcon,
  DOCUMENT_UPLOADED: FileTextIcon,
  DOCUMENT_DECIDED: FileTextIcon,
  AGREEMENT_STATUS: FileSignatureIcon,
  NEW_COMMENT: MessageSquareIcon,
  NEW_CUSTOMER: UserCheckIcon,
};

export function iconFor(type: NotificationItem["type"]): ComponentType<IconProps> {
  return (type && ICONS[type]) || FileTextIcon;
}
