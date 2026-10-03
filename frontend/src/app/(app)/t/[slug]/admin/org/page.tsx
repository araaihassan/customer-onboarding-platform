"use client";

import { useState } from "react";
import { BuildingIcon, LayersIcon, PlusIcon } from "@/components/icons";
import { TeamMembers } from "@/components/admin/TeamMembers";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Card, CardHeader } from "@/components/ui/Card";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import {
  useCreateDepartment,
  useCreateTeam,
  useDepartments,
  useTeams,
  useUpdateDepartment,
  useUsers,
} from "@/lib/api/admin";
import type { Department, Team, User } from "@/lib/api/admin";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * Departments and teams — the org structure the DEPARTMENT and TEAM scopes
 * resolve against.
 *
 * Both lists are read with the same permission that writes them: department.manage
 * and team.manage are ALL-only, and there is no separate view permission for
 * either. So a user without the manage permission sees nothing here rather than a
 * read-only list, which is what the API actually enforces.
 *
 * Neither entity has an update or a delete endpoint in sub-project 1, so neither
 * has a control here. An affordance for something the API cannot do is worse than
 * its absence.
 *
 * The screen title belongs to the shell header, which owns the <h1>; everything
 * here starts at <h2>.
 */
export default function OrgPage() {
  const canManageDepartments = useHasPermission("department.manage");
  const canManageTeams = useHasPermission("team.manage");
  const canViewUsers = useHasPermission("user.view");

  const departments = useDepartments(canManageDepartments);
  const teams = useTeams(canManageTeams);
  const createDepartment = useCreateDepartment();
  const createTeam = useCreateTeam();
  const updateDepartment = useUpdateDepartment();
  // Heads are resolved to names from the first page of users (fixed page size
  // of 25 -- a known limit; a head beyond it shows a neutral fallback).
  const users = useUsers("", 0, canManageDepartments && canViewUsers);
  const userList = users.data?.content ?? [];
  const [editingDepartment, setEditingDepartment] = useState<Department | null>(null);

  const [creating, setCreating] = useState<"department" | "team" | null>(null);
  const [selectedTeam, setSelectedTeam] = useState<Team | null>(null);

  useSetPageHeader(t("admin.org.title"));

  return (
    <section>
      <h2 className="sr-only">{t("admin.org.title")}</h2>

      <div
        className="grid items-start lg:grid-cols-2"
        style={{ gap: "var(--ob-space-20)" }}
      >
        <Card>
          <CardHeader
            title={t("admin.departments.title")}
            count={departments.isLoading ? undefined : (departments.data?.length ?? 0)}
          />

          {!canManageDepartments ? (
            <EmptyState
              icon={<BuildingIcon size={24} />}
              title={t("admin.org.noAccess")}
              description={t("admin.org.noAccessHint")}
            />
          ) : departments.isLoading ? (
            <SkeletonRows rows={3} height={40} />
          ) : departments.isError ? (
            <EmptyState
              icon={<BuildingIcon size={24} />}
              title={t("common.error")}
              action={
                <Button type="button" variant="secondary" onClick={() => void departments.refetch()}>
                  {t("common.retry")}
                </Button>
              }
            />
          ) : departments.data?.length === 0 ? (
            <EmptyState
              icon={<BuildingIcon size={24} />}
              title={t("admin.departments.empty")}
              description={t("admin.departments.emptyHint")}
            />
          ) : (
            <ul className="flex flex-col">
              {departments.data?.map((department) => (
                <Row
                  key={department.id}
                  name={department.name ?? ""}
                  detail={department.description}
                  head={headLabel(department, userList, canViewUsers)}
                  editLabel={t("admin.departments.edit.for", { name: department.name ?? "" })}
                  onEdit={() => {
                    updateDepartment.reset();
                    setEditingDepartment(department);
                  }}
                />
              ))}
            </ul>
          )}

          {canManageDepartments && (
            <div className="flex justify-end" style={{ marginTop: "var(--ob-space-16)" }}>
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  createDepartment.reset();
                  setCreating("department");
                }}
                style={{ gap: "var(--ob-space-6)", height: "var(--ob-control-height-sm)" }}
              >
                <PlusIcon size={13} />
                {t("admin.departments.create")}
              </Button>
            </div>
          )}
        </Card>

        <Card>
          <CardHeader
            title={t("admin.teams.title")}
            count={teams.isLoading ? undefined : (teams.data?.length ?? 0)}
          />

          {!canManageTeams ? (
            <EmptyState
              icon={<LayersIcon size={24} />}
              title={t("admin.org.noAccess")}
              description={t("admin.org.noAccessHint")}
            />
          ) : teams.isLoading ? (
            <SkeletonRows rows={3} height={40} />
          ) : teams.isError ? (
            <EmptyState
              icon={<LayersIcon size={24} />}
              title={t("common.error")}
              action={
                <Button type="button" variant="secondary" onClick={() => void teams.refetch()}>
                  {t("common.retry")}
                </Button>
              }
            />
          ) : teams.data?.length === 0 ? (
            <EmptyState
              icon={<LayersIcon size={24} />}
              title={t("admin.teams.empty")}
              description={t("admin.teams.emptyHint")}
            />
          ) : (
            <ul className="flex flex-col">
              {teams.data?.map((team) => (
                <li
                  key={team.id}
                  onClick={() => setSelectedTeam(team)}
                  className="border-t border-line-faint first:border-t-0 cursor-pointer hover:bg-surface-active"
                  style={{ padding: "var(--ob-space-10) 0" }}
                  role="button"
                  tabIndex={0}
                >
                  <p
                    className="truncate text-ink"
                    style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
                  >
                    {team.name}
                  </p>
                  {(() => {
                    const detail = [
                      team.description,
                      departments.data?.find((d) => d.id === team.departmentId)?.name,
                    ]
                      .filter(Boolean)
                      .join(" · ");
                    return detail ? (
                      <p
                        className="truncate text-text-subtle"
                        style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
                      >
                        {detail}
                      </p>
                    ) : null;
                  })()}
                </li>
              ))}
            </ul>
          )}

          {canManageTeams && (
            <div className="flex justify-end" style={{ marginTop: "var(--ob-space-16)" }}>
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  createTeam.reset();
                  setCreating("team");
                }}
                style={{ gap: "var(--ob-space-6)", height: "var(--ob-control-height-sm)" }}
              >
                <PlusIcon size={13} />
                {t("admin.teams.create")}
              </Button>
            </div>
          )}
        </Card>
      </div>

      {selectedTeam && (
        <div style={{ marginTop: "var(--ob-space-20)" }}>
          <TeamMembers team={selectedTeam} />
        </div>
      )}

      {creating === "department" && (
        <Dialog title={t("admin.departments.create")} onClose={() => setCreating(null)}>
          <OrgForm
            submitLabel={t("admin.departments.create.submit")}
            pending={createDepartment.isPending}
            error={createDepartment.isError ? t("common.error") : undefined}
            onCancel={() => setCreating(null)}
            onSubmit={(values) =>
              createDepartment.mutate(values, { onSuccess: () => setCreating(null) })
            }
          />
        </Dialog>
      )}

      {editingDepartment && (
        <Dialog title={t("admin.departments.edit.title")} onClose={() => setEditingDepartment(null)}>
          <DepartmentEditForm
            department={editingDepartment}
            heads={canViewUsers ? userList : undefined}
            pending={updateDepartment.isPending}
            error={
              updateDepartment.isError
                ? updateDepartment.error instanceof ApiError
                  ? parseProblemDetail(updateDepartment.error.message)
                  : t("common.error")
                : undefined
            }
            onCancel={() => setEditingDepartment(null)}
            onSubmit={(body) =>
              updateDepartment.mutate(
                { id: editingDepartment.id ?? "", body },
                { onSuccess: () => setEditingDepartment(null) },
              )
            }
          />
        </Dialog>
      )}

      {creating === "team" && (
        <Dialog title={t("admin.teams.create")} onClose={() => setCreating(null)}>
          <OrgForm
            submitLabel={t("admin.teams.create.submit")}
            pending={createTeam.isPending}
            error={createTeam.isError ? t("common.error") : undefined}
            departments={canManageDepartments ? (departments.data ?? []) : undefined}
            onCancel={() => setCreating(null)}
            onSubmit={(values) => createTeam.mutate(values, { onSuccess: () => setCreating(null) })}
          />
        </Dialog>
      )}
    </section>
  );
}

/**
 * The head's NAME, never its uuid in human text. A head not in the fetched page
 * (or an actor who cannot read users) gets a neutral label instead.
 */
function headLabel(department: Department, users: User[], canViewUsers: boolean): string {
  if (!department.headUserId) return t("admin.departments.noHead");
  const head = canViewUsers ? users.find((u) => u.id === department.headUserId) : undefined;
  return head?.fullName
    ? t("admin.departments.head", { name: head.fullName })
    : t("admin.departments.headUnknown");
}

function Row({
  name,
  detail,
  head,
  editLabel,
  onEdit,
}: {
  name: string;
  detail?: string;
  head: string;
  editLabel: string;
  onEdit: () => void;
}) {
  return (
    <li
      className="flex items-center border-t border-line-faint first:border-t-0"
      style={{ padding: "var(--ob-space-10) 0", gap: "var(--ob-space-11)" }}
    >
      <div className="flex-1 min-w-0">
      <p
        className="truncate text-ink"
        style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
      >
        {name}
      </p>
      {detail && (
        <p
          className="truncate text-text-subtle"
          style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
        >
          {detail}
        </p>
      )}
      <p
        className="truncate text-text-subtle"
        style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
      >
        {head}
      </p>
      </div>
      <Button
        type="button"
        variant="secondary"
        aria-label={editLabel}
        onClick={onEdit}
        style={{ height: "var(--ob-control-height-sm)" }}
      >
        {t("admin.departments.edit")}
      </Button>
    </li>
  );
}

/**
 * PUT is a full replace: name, description AND headUserId always travel
 * together. "No head" is an explicit null; without user.view the select is
 * hidden and the current head is resent unchanged rather than blanked.
 */
function DepartmentEditForm({
  department,
  heads,
  pending,
  error,
  onSubmit,
  onCancel,
}: {
  department: Department;
  heads?: User[];
  pending: boolean;
  error?: string;
  onSubmit: (body: { name: string; description: string; headUserId: string | null }) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(department.name ?? "");
  const [description, setDescription] = useState(department.description ?? "");
  const [headUserId, setHeadUserId] = useState(department.headUserId ?? "");
  const [nameError, setNameError] = useState<string>();
  const candidates = (heads ?? []).filter((u) => u.status === "ACTIVE" && u.userType === "INTERNAL");

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        const trimmed = name.trim();
        if (!trimmed) {
          setNameError(t("customer.form.required"));
          return;
        }
        onSubmit({
          name: trimmed,
          description: description.trim(),
          headUserId: headUserId || null,
        });
      }}
    >
      <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
        <Field
          label={t("admin.org.field.name")}
          value={name}
          error={nameError}
          onChange={(event) => {
            setName(event.target.value);
            setNameError(undefined);
          }}
        />
        <Field
          label={t("admin.org.field.description")}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
        {heads && (
          <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
            <label
              htmlFor="department-head"
              className="text-text-muted"
              style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
            >
              {t("admin.org.field.head")}
            </label>
            <select
              id="department-head"
              value={headUserId}
              onChange={(event) => setHeadUserId(event.target.value)}
              className="bg-surface border border-line text-ink"
              style={{
                height: "var(--ob-control-height)",
                borderRadius: "var(--ob-radius-9)",
                padding: "0 var(--ob-space-11)",
                font: "13px/1.3 var(--ob-font-family-ui)",
              }}
            >
              <option value="">{t("admin.org.field.head.none")}</option>
              {headUserId && !candidates.some((c) => c.id === headUserId) && (
                <option value={headUserId}>{t("admin.departments.headUnknown")}</option>
              )}
              {candidates.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.fullName}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>

      {error && (
        <p
          role="alert"
          style={{
            color: "var(--ob-risk-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
          }}
        >
          {error}
        </p>
      )}

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onCancel}>
          {t("common.cancel")}
        </Button>
        <Button type="submit" disabled={pending}>
          {t("common.save")}
        </Button>
      </DialogActions>
    </form>
  );
}

/**
 * Name, description, and — for a team — the department it belongs to.
 *
 * The department select is omitted rather than disabled when the user cannot read
 * departments: a team without one is valid, and a disabled control offering a
 * choice that was never available reads as broken.
 */
function OrgForm({
  submitLabel,
  pending,
  error,
  departments,
  onSubmit,
  onCancel,
}: {
  submitLabel: string;
  pending: boolean;
  error?: string;
  departments?: Department[];
  onSubmit: (values: { name: string; description: string; departmentId?: string }) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [departmentId, setDepartmentId] = useState("");
  const [nameError, setNameError] = useState<string>();

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        const trimmed = name.trim();
        if (!trimmed) {
          setNameError(t("customer.form.required"));
          return;
        }
        onSubmit({
          name: trimmed,
          description: description.trim(),
          ...(departmentId ? { departmentId } : {}),
        });
      }}
    >
      <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
        <Field
          label={t("admin.org.field.name")}
          value={name}
          error={nameError}
          onChange={(event) => {
            setName(event.target.value);
            setNameError(undefined);
          }}
        />
        <Field
          label={t("admin.org.field.description")}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />

        {departments && departments.length > 0 && (
          <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
            <label
              htmlFor="team-department"
              className="text-text-muted"
              style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
            >
              {t("admin.org.field.department")}
            </label>
            <select
              id="team-department"
              value={departmentId}
              onChange={(event) => setDepartmentId(event.target.value)}
              className="bg-surface border border-line text-ink"
              style={{
                height: "var(--ob-control-height)",
                borderRadius: "var(--ob-radius-9)",
                padding: "0 var(--ob-space-11)",
                font: "13px/1.3 var(--ob-font-family-ui)",
              }}
            >
              <option value="">{t("admin.org.field.department.none")}</option>
              {departments.map((department) => (
                <option key={department.id} value={department.id}>
                  {department.name}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>

      {error && (
        <p
          role="alert"
          style={{
            color: "var(--ob-risk-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
          }}
        >
          {error}
        </p>
      )}

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onCancel}>
          {t("common.cancel")}
        </Button>
        <Button type="submit" disabled={pending}>
          {submitLabel}
        </Button>
      </DialogActions>
    </form>
  );
}
