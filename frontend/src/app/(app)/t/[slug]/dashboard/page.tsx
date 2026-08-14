"use client";

import { LayoutDashboardIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { EmptyState } from "@/components/ui/States";
import { t } from "@/lib/i18n";

/**
 * The dashboard is sub-project 8's screen (QA Q16: per-role layouts). This is the
 * placeholder that stands in until then, and it names what is coming on purpose —
 * an empty screen reads as broken, a screen that says what will fill it reads as
 * deliberate.
 *
 * No meta line: the header's meta is for machine-generated values, and there is
 * no honest count to put there yet.
 */
export default function DashboardPage() {
  useSetPageHeader(t("dashboard.title"));

  return (
    <EmptyState
      icon={<LayoutDashboardIcon size={28} />}
      title={t("dashboard.empty.title")}
      description={t("dashboard.empty.description")}
    />
  );
}
