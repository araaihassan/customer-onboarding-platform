"use client";

import { useState } from "react";
import { BuildingIcon, LayersIcon, PlusIcon } from "@/components/icons";
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
} from "@/lib/api/admin";
import type { Department } from "@/lib/api/admin";
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

  const departments = useDepartments(canManageDepartments);
  const teams = useTeams(canManageTeams);
  const createDepartment = useCreateDepartment();
  const createTeam = useCreateTeam();

  const [creating, setCreating] = useState<"department" | "team" | null>(null);

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
                <Row
                  key={team.id}
                  name={team.name ?? ""}
                  detail={
                    // The owning department, resolved to its name — a raw uuid in
                    // a list of teams is an identifier nobody can act on.
                    [
                      team.description,
                      departments.data?.find((d) => d.id === team.departmentId)?.name,
                    ]
                      .filter(Boolean)
                      .join(" · ")
                  }
                />
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

function Row({ name, detail }: { name: string; detail?: string }) {
  return (
    <li
      className="border-t border-border-subtle first:border-t-0"
      style={{ padding: "var(--ob-space-10) 0" }}
    >
      <p
        className="truncate text-text-primary"
        style={{ font: "500 var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)" }}
      >
        {name}
      </p>
      {detail && (
        <p
          className="truncate text-text-muted"
          style={{ font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)" }}
        >
          {detail}
        </p>
      )}
    </li>
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
              className="text-text-secondary"
              style={{ font: "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}
            >
              {t("admin.org.field.department")}
            </label>
            <select
              id="team-department"
              value={departmentId}
              onChange={(event) => setDepartmentId(event.target.value)}
              className="bg-bg-surface border border-border-default text-text-primary"
              style={{
                height: "var(--ob-control-height)",
                borderRadius: "var(--ob-radius-control)",
                padding: "0 var(--ob-space-11)",
                font: "var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)",
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
            color: "var(--ob-status-blocked-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
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
