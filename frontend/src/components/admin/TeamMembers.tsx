"use client";

import { useState } from "react";
import { PlusIcon, XIcon } from "@/components/icons";
import { Button } from "@/components/ui/Button";
import { Card, CardHeader } from "@/components/ui/Card";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import type { Team, TeamMember } from "@/lib/api/admin";
import {
  useTeamMembers,
  useAddTeamMember,
  useRemoveTeamMember,
  useUsers,
} from "@/lib/api/admin";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

export function TeamMembers({ team }: { team: Team }) {
  const canManageTeams = useHasPermission("team.manage");
  const canViewUsers = useHasPermission("user.view");

  // springdoc marks every property optional, so the generated `TeamView.id` is
  // `string | undefined`. Same `?? ""` narrowing RoleEditor and ContactList use.
  const teamId = team.id ?? "";

  const members = useTeamMembers(teamId, canManageTeams);
  const availableUsers = useUsers("", 0, canManageTeams && canViewUsers);
  const addMember = useAddTeamMember();
  const removeMember = useRemoveTeamMember();

  const [showAddMember, setShowAddMember] = useState(false);
  const [selectedUserId, setSelectedUserId] = useState("");

  const handleAddMember = () => {
    if (selectedUserId) {
      addMember.mutate(
        { teamId, userId: selectedUserId },
        {
          onSuccess: () => {
            setShowAddMember(false);
            setSelectedUserId("");
          },
        }
      );
    }
  };

  // Filter out users who are already members
  const memberIds = new Set(members.data?.map((m) => m.userId) ?? []);
  const availableToAdd = availableUsers.data?.content?.filter(
    (u) => !memberIds.has(u.id)
  ) ?? [];

  return (
    <>
      <Card>
        <CardHeader
          title={t("admin.team.members.title")}
          count={members.isLoading ? undefined : (members.data?.length ?? 0)}
        />

        {!canManageTeams ? (
          <EmptyState
            icon={null}
            title={t("admin.org.noAccess")}
            description={t("admin.org.noAccessHint")}
          />
        ) : members.isLoading ? (
          <SkeletonRows rows={3} height={40} />
        ) : members.isError ? (
          <EmptyState
            icon={null}
            title={t("common.error")}
            action={
              <Button
                type="button"
                variant="secondary"
                onClick={() => void members.refetch()}
              >
                {t("common.retry")}
              </Button>
            }
          />
        ) : members.data?.length === 0 ? (
          <EmptyState
            icon={null}
            title={t("admin.team.members.empty")}
            description={t("admin.team.members.emptyHint")}
          />
        ) : (
          <ul className="flex flex-col">
            {members.data?.map((member) => (
              <MemberRow
                key={member.userId}
                member={member}
                onRemove={() => {
                  removeMember.mutate({ teamId, userId: member.userId ?? "" });
                }}
                isRemoving={removeMember.isPending}
              />
            ))}
          </ul>
        )}

        {canManageTeams && availableToAdd.length > 0 && (
          <div className="flex justify-end" style={{ marginTop: "var(--ob-space-16)" }}>
            <Button
              type="button"
              variant="secondary"
              onClick={() => setShowAddMember(true)}
              style={{ gap: "var(--ob-space-6)", height: "var(--ob-control-height-sm)" }}
            >
              <PlusIcon size={13} />
              {t("admin.team.members.add")}
            </Button>
          </div>
        )}
      </Card>

      {showAddMember && (
        <Dialog
          title={t("admin.team.members.add")}
          onClose={() => {
            setShowAddMember(false);
            setSelectedUserId("");
          }}
        >
          <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
            <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
              <label
                htmlFor="select-user"
                className="text-text-secondary"
                style={{
                  font: "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
                }}
              >
                {t("admin.team.members.selectUser")}
              </label>
              <select
                id="select-user"
                value={selectedUserId}
                onChange={(e) => setSelectedUserId(e.target.value)}
                className="bg-bg-surface border border-border-default text-text-primary"
                style={{
                  height: "var(--ob-control-height)",
                  borderRadius: "var(--ob-radius-control)",
                  padding: "0 var(--ob-space-11)",
                  font: "var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)",
                }}
              >
                <option value="">{t("common.select")}</option>
                {availableToAdd.map((user) => (
                  <option key={user.id} value={user.id}>
                    {user.fullName} ({user.email})
                  </option>
                ))}
              </select>
            </div>

            <DialogActions>
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  setShowAddMember(false);
                  setSelectedUserId("");
                }}
              >
                {t("common.cancel")}
              </Button>
              <Button
                type="button"
                disabled={!selectedUserId || addMember.isPending}
                onClick={handleAddMember}
              >
                {t("common.add")}
              </Button>
            </DialogActions>
          </div>
        </Dialog>
      )}
    </>
  );
}

function MemberRow({
  member,
  onRemove,
  isRemoving,
}: {
  member: TeamMember;
  onRemove: () => void;
  isRemoving: boolean;
}) {
  return (
    <li
      className="border-t border-border-subtle first:border-t-0 flex items-center justify-between"
      style={{ padding: "var(--ob-space-10) 0" }}
    >
      <div className="flex-1">
        <p
          className="truncate text-text-primary"
          style={{
            font: "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
          }}
        >
          {member.fullName}
        </p>
        <p
          className="truncate text-text-muted"
          style={{
            font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-mono)",
          }}
        >
          {member.email}
        </p>
      </div>
      <Button
        type="button"
        // Not "ghost": Button offers primary and secondary only, and the earlier
        // "ghost" was a type error that already rendered as secondary — esbuild
        // strips types without checking them, so only `tsc`/`next build` saw it.
        variant="secondary"
        onClick={onRemove}
        disabled={isRemoving}
        style={{ padding: "var(--ob-space-6)" }}
        title={t("admin.team.members.remove")}
      >
        <XIcon size={16} />
      </Button>
    </li>
  );
}
