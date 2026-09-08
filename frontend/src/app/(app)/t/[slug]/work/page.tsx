"use client";

import { WorkBoard } from "@/components/task/WorkBoard";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { t } from "@/lib/i18n";

/**
 * The cross-case "My work" board's route (Task 28). Thin by design, same
 * shape as `dashboard/page.tsx`: the shell header gets the screen's own
 * title, everything else is `WorkBoard`'s -- including its own on-page
 * headline/sub copy, which is content, not chrome, and belongs with the
 * board it describes rather than the shell.
 */
export default function WorkPage() {
  useSetPageHeader(t("nav.work"));

  return <WorkBoard />;
}
