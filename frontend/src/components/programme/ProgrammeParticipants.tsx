"use client";

import { useState } from "react";
import { PlusIcon } from "@/components/icons";
import { Button } from "@/components/ui/Button";
import { Card, CardHeader } from "@/components/ui/Card";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { useUsers } from "@/lib/api/admin";
import { useAddProgrammeParticipant } from "@/lib/api/programmes";
import type { AddProgrammeParticipantRequest } from "@/lib/api/programmes";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

type RelationshipType = NonNullable<AddProgrammeParticipantRequest["relationshipType"]>;

const RELATIONSHIP_TYPES: RelationshipType[] = ["OWNER", "ASSIGNEE", "PARTICIPANT", "APPROVER", "CREATOR"];

/**
 * The programme's participants panel (design spec §6.3, §8.1).
 *
 * Participation is READ-ONLY over the programme container by design: adding
 * someone here grants them nothing over its journeys by itself. §6.3's
 * resolution of Q20's own contradiction ("sees the whole project" vs. "never
 * grants access a viewer could not otherwise obtain") is that adding a
 * participant only OFFERS to also add them as a `CaseParticipant` on the
 * programme's journeys, as an explicit, audited, individually revocable
 * escalation -- `alsoGrantJourneyAccess` below is exactly that offer, and it
 * defaults to false so the read-only case is the one nobody has to opt out of.
 *
 * There is deliberately no roster rendered here. The design spec's own API
 * surface (§7) and `ProgrammeController` list only
 * `POST /programmes/{id}/participants` and its `/remove` -- no `GET`
 * anywhere -- so there is nothing server-side to list from; `ProgrammeDetailView`
 * itself carries no participants field either. This panel is therefore a
 * write-only action, with a running "added this session" log so a caller who
 * adds several people in one sitting sees confirmation of what they just did.
 * That log is explicitly NOT a persisted roster -- it starts empty on every
 * page load -- and its own copy says so, rather than implying a roster that
 * silently forgets everyone the moment the page is left. A real roster needs
 * a `GET` endpoint this sub-project never designed.
 */
export function ProgrammeParticipants({ programmeId }: { programmeId: string }) {
  const canManage = useHasPermission("programme.manage");
  const canViewUsers = useHasPermission("user.view");

  const users = useUsers("", 0, canManage && canViewUsers);
  const addParticipant = useAddProgrammeParticipant();

  const [adding, setAdding] = useState(false);
  const [userId, setUserId] = useState("");
  const [relationshipType, setRelationshipType] = useState<RelationshipType>("PARTICIPANT");
  const [alsoGrantJourneyAccess, setAlsoGrantJourneyAccess] = useState(false);
  const [added, setAdded] = useState<{ userId: string; fullName: string; relationshipType: RelationshipType }[]>([]);

  // Courtesy hiding only -- the server's own PROGRAMME_MANAGE gate on
  // ProgrammeMembershipService is the real control (useHasPermission's own
  // contract).
  if (!canManage) return null;

  function reset() {
    setUserId("");
    setRelationshipType("PARTICIPANT");
    setAlsoGrantJourneyAccess(false);
    addParticipant.reset();
  }

  function submit() {
    if (!userId) return;
    const user = users.data?.content?.find((candidate) => candidate.id === userId);
    addParticipant.mutate(
      { programmeId, userId, relationshipType, alsoGrantJourneyAccess },
      {
        onSuccess: () => {
          setAdded((previous) => [
            { userId, fullName: user?.fullName ?? userId, relationshipType },
            ...previous,
          ]);
          setAdding(false);
          reset();
        },
      },
    );
  }

  return (
    <>
      <Card>
        <CardHeader
          title={t("programme.participants.title")}
          action={
            <Button
              type="button"
              variant="secondary"
              onClick={() => {
                reset();
                setAdding(true);
              }}
              style={{ gap: "var(--ob-space-6)", height: "var(--ob-control-height-sm)" }}
            >
              <PlusIcon size={13} />
              {t("programme.participants.add")}
            </Button>
          }
        />

        <p
          className="text-text-subtle"
          style={{
            font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
            marginBottom: "var(--ob-space-11)",
          }}
        >
          {t("programme.participants.hint")}
        </p>

        {added.length === 0 ? (
          <p
            className="text-text-faint"
            style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
          >
            {t("programme.participants.empty")}
          </p>
        ) : (
          <ul className="flex flex-col">
            {added.map((entry, index) => (
              <li
                key={`${entry.userId}-${index}`}
                className="flex items-center justify-between border-t border-line-faint first:border-t-0"
                style={{ padding: "var(--ob-space-8) 0" }}
              >
                <span
                  className="truncate text-ink"
                  style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
                >
                  {entry.fullName}
                </span>
                <span
                  className="text-text-subtle"
                  style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-data)" }}
                >
                  {t(`programme.relationship.${entry.relationshipType}`)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {adding && (
        <Dialog title={t("programme.participants.add")} onClose={() => setAdding(false)}>
          <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
            <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
              <label
                htmlFor="programme-participant-user"
                className="text-text-muted"
                style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
              >
                {t("programme.participants.selectUser")}
              </label>
              <select
                id="programme-participant-user"
                value={userId}
                onChange={(event) => setUserId(event.target.value)}
                className="bg-surface border border-line text-ink"
                style={{
                  height: "var(--ob-control-height)",
                  borderRadius: "var(--ob-radius-9)",
                  padding: "0 var(--ob-space-11)",
                  font: "13px/1.3 var(--ob-font-family-ui)",
                }}
              >
                <option value="">{t("common.select")}</option>
                {(users.data?.content ?? []).map((user) => (
                  <option key={user.id} value={user.id}>
                    {user.fullName} ({user.email})
                  </option>
                ))}
              </select>
            </div>

            <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
              <label
                htmlFor="programme-participant-relationship"
                className="text-text-muted"
                style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
              >
                {t("programme.participants.relationship")}
              </label>
              <select
                id="programme-participant-relationship"
                value={relationshipType}
                onChange={(event) => setRelationshipType(event.target.value as RelationshipType)}
                className="bg-surface border border-line text-ink"
                style={{
                  height: "var(--ob-control-height)",
                  borderRadius: "var(--ob-radius-9)",
                  padding: "0 var(--ob-space-11)",
                  font: "13px/1.3 var(--ob-font-family-ui)",
                }}
              >
                {RELATIONSHIP_TYPES.map((value) => (
                  <option key={value} value={value}>
                    {t(`programme.relationship.${value}`)}
                  </option>
                ))}
              </select>
            </div>

            <label className="flex items-center" style={{ gap: "var(--ob-space-8)" }}>
              <input
                type="checkbox"
                checked={alsoGrantJourneyAccess}
                onChange={(event) => setAlsoGrantJourneyAccess(event.target.checked)}
              />
              <span
                className="text-text-muted"
                style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
              >
                {t("programme.participants.alsoGrantJourneyAccess")}
              </span>
            </label>

            {/* Rendered empty rather than conditionally, so the live region exists
                before there is anything to announce -- same shape as the customer
                deactivation dialog's own error live-region. */}
            <p
              role="alert"
              style={{
                color: "var(--ob-risk-fg)",
                font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
              }}
            >
              {addParticipant.isError ? t("common.error") : ""}
            </p>

            <DialogActions>
              <Button type="button" variant="secondary" onClick={() => setAdding(false)}>
                {t("common.cancel")}
              </Button>
              <Button type="button" disabled={!userId || addParticipant.isPending} onClick={submit}>
                {t("common.add")}
              </Button>
            </DialogActions>
          </div>
        </Dialog>
      )}
    </>
  );
}
