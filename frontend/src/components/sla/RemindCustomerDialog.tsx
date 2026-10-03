"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { SkeletonRows } from "@/components/ui/States";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail } from "@/lib/api/cases";
import { useDocumentRequests, useRemindRequest, type DocumentRequest } from "@/lib/api/documents";
import { formatDayMonth } from "@/lib/format/date";
import { t } from "@/lib/i18n";
import { InlineError, SlaEyebrow } from "./dialogParts";

const dataFont = { fontFamily: "var(--ob-font-family-data)" } as const;

/**
 * Nudge the customer on each OPEN document request of a case. A 409 means the server's 24-hour
 * throttle: shown on that row, dialog stays open. A 422 shows the server's detail. A request with
 * no contact (`requestedOfContactId` absent) cannot be reminded, so its button is disabled up front.
 */
export function RemindCustomerDialog({ caseId, onClose }: { caseId: string; onClose: () => void }) {
  const requests = useDocumentRequests(caseId);
  const remind = useRemindRequest();
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({});

  const open = (requests.data?.content ?? []).filter((r) => r.status === "OPEN");

  function send(request: DocumentRequest) {
    const id = request.id ?? "";
    setRowErrors((prev) => ({ ...prev, [id]: "" }));
    remind.mutate(
      { requestId: id, caseId },
      {
        onError: (e) => {
          const message =
            e instanceof ApiError
              ? e.status === 409
                ? t("sla.remind.tooSoon")
                : parseProblemDetail(e.message)
              : t("common.error");
          setRowErrors((prev) => ({ ...prev, [id]: message }));
        },
      },
    );
  }

  let body;
  if (requests.isError) {
    body = <InlineError>{t("sla.remind.error")}</InlineError>;
  } else if (requests.isLoading) {
    body = <SkeletonRows rows={2} height={56} />;
  } else if (open.length === 0) {
    body = (
      <p className="text-text-subtle" style={{ font: "13px/1.5 var(--ob-font-family-ui)" }}>
        {t("sla.remind.empty")}
      </p>
    );
  } else {
    body = (
      <ul className="flex flex-col" style={{ listStyle: "none", padding: 0, margin: 0, gap: "var(--ob-space-12)" }}>
        {open.map((request) => {
          const id = request.id ?? "";
          const noContact = !request.requestedOfContactId;
          const history =
            (request.remindersSent ?? 0) > 0
              ? t("sla.remind.history", { count: String(request.remindersSent), date: formatDayMonth(request.lastRemindedAt) })
              : t("sla.remind.never");
          return (
            <li
              key={id}
              style={{ border: "1px solid var(--ob-line)", borderRadius: "var(--ob-radius-10)", padding: "10px 12px" }}
            >
              <div className="flex items-start justify-between" style={{ gap: "var(--ob-space-12)" }}>
                <div>
                  <p className="text-ink" style={{ font: "500 13px/1.4 var(--ob-font-family-ui)" }}>
                    {request.description}
                  </p>
                  <p className="text-text-subtle" style={{ font: "11.5px/1.5 var(--ob-font-family-ui)" }}>
                    <span style={dataFont}>{request.category}</span>
                  </p>
                  <p className="text-text-subtle" style={{ font: "11.5px/1.5 var(--ob-font-family-ui)" }}>
                    <span style={dataFont}>{history}</span>
                  </p>
                  {noContact && (
                    <p className="text-text-subtle" style={{ font: "11.5px/1.5 var(--ob-font-family-ui)" }}>
                      {t("sla.remind.noContact")}
                    </p>
                  )}
                </div>
                <Button
                  type="button"
                  variant="small-secondary"
                  disabled={noContact || (remind.isPending && remind.variables?.requestId === id)}
                  onClick={() => send(request)}
                >
                  {t("sla.remind.button")}
                </Button>
              </div>
              {rowErrors[id] && <InlineError>{rowErrors[id]}</InlineError>}
            </li>
          );
        })}
      </ul>
    );
  }

  return (
    <Dialog title={t("sla.remind.title")} eyebrow={<SlaEyebrow />} onClose={onClose} maxWidth={480}>
      {body}
      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.close")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
