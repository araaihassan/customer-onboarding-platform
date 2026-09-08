"use client";

import { useState } from "react";
import type { ReactNode } from "react";
import { PlusIcon, SearchIcon, UsersIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { Pagination } from "@/components/ui/Pagination";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import {
  useAssignRole,
  useDeactivateUser,
  useDepartments,
  useInviteUser,
  useRoles,
  useUnassignRole,
  useUpdateUser,
  useUsers,
} from "@/lib/api/admin";
import type { Role, User } from "@/lib/api/admin";
import { useAuth } from "@/lib/auth/useAuth";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { useDebounced } from "@/lib/useDebounced";
import { t } from "@/lib/i18n";

/**
 * The people who work here: who they are, what they hold, and whether they are
 * still active.
 *
 * Inviting a colleague and creating one are the same operation — the account is
 * created INVITED with no password hash and the activation email goes out in the
 * same transaction — so there is one button, and it says "Invite user".
 *
 * The screen title belongs to the shell header, which owns the <h1>; everything
 * here starts at <h2>.
 */
export default function UsersPage() {
  const canView = useHasPermission("user.view");
  const canManage = useHasPermission("user.manage");
  const canViewRoles = useHasPermission("role.view");

  const [searchInput, setSearchInput] = useState("");
  const search = useDebounced(searchInput, 250);
  const [page, setPage] = useState(0);
  const [inviting, setInviting] = useState(false);
  const [managingRolesFor, setManagingRolesFor] = useState<User | null>(null);
  const [deactivating, setDeactivating] = useState<User | null>(null);
  const [editing, setEditing] = useState<User | null>(null);

  const users = useUsers(search, page, canView);
  // Only fetched when the screen can actually do something with them: role
  // membership is rendered by name, and that needs the catalog.
  const roles = useRoles(canViewRoles);
  const invite = useInviteUser();
  const deactivate = useDeactivateUser();
  const update = useUpdateUser();

  useSetPageHeader(t("admin.users.title"));

  const list = users.data?.content ?? [];
  const total = users.data?.totalElements ?? 0;
  const totalPages = users.data?.totalPages ?? 0;
  const searching = Boolean(search.trim());
  // A failed catalog fetch is not "this user holds no roles". Rendering it as
  // one would make an authorization screen assert something it does not know.
  const rolesUnavailable = canViewRoles && roles.isError;

  // Re-read from the freshly invalidated list, so the dialog reflects the
  // assignment that just landed rather than the snapshot it opened with.
  const managing = managingRolesFor
    ? (list.find((user) => user.id === managingRolesFor.id) ?? managingRolesFor)
    : null;

  return (
    <section>
      <h2 className="sr-only">{t("admin.users.title")}</h2>

      <div
        className="flex flex-wrap items-center"
        style={{ gap: "var(--ob-space-11)", marginBottom: "var(--ob-space-16)" }}
      >
        <div className="relative">
          <span
            aria-hidden="true"
            className="absolute text-text-faint"
            style={{ left: "var(--ob-space-10)", top: "50%", transform: "translateY(-50%)" }}
          >
            <SearchIcon size={14} />
          </span>
          {/* A real <label>, visually hidden. A placeholder is not an accessible
              name and disappears the moment someone types. */}
          <label className="sr-only" htmlFor="user-search">
            {t("admin.users.search")}
          </label>
          <input
            id="user-search"
            type="search"
            value={searchInput}
            onChange={(event) => {
              setSearchInput(event.target.value);
              // Page 3 of a search that no longer applies is a blank screen with
              // no explanation.
              setPage(0);
            }}
            placeholder={t("admin.users.search")}
            className="bg-surface border border-line text-ink"
            style={{
              height: "var(--ob-control-height)",
              width: 260,
              borderRadius: "var(--ob-radius-9)",
              // --ob-space-28 doesn't exist in the new spacing scale (it tops
              // out at 26 before jumping to 40) -- 26 is the closest surviving
              // step, close enough to still clear the search icon at left:10.
              padding: "0 var(--ob-space-11) 0 var(--ob-space-26)",
              font: "13px/1.3 var(--ob-font-family-ui)",
            }}
          />
        </div>

        <div className="flex-1" />

        {/* A count is a machine-generated value, so it is mono. */}
        {!users.isLoading && (
          <span
            className="text-text-subtle whitespace-nowrap"
            style={{ font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)" }}
          >
            {t("admin.users.count", { count: String(total) })}
          </span>
        )}

        {canManage && (
          <Button
            type="button"
            onClick={() => {
              invite.reset();
              setInviting(true);
            }}
            style={{ gap: "var(--ob-space-6)" }}
          >
            <PlusIcon size={14} />
            {t("admin.users.invite")}
          </Button>
        )}
      </div>

      {users.isLoading ? (
        <SkeletonRows rows={6} height={56} />
      ) : users.isError ? (
        <EmptyState
          icon={<UsersIcon size={28} />}
          title={t("common.error")}
          action={
            <Button type="button" variant="secondary" onClick={() => void users.refetch()}>
              {t("common.retry")}
            </Button>
          }
        />
      ) : list.length === 0 ? (
        <EmptyState
          icon={<UsersIcon size={28} />}
          title={searching ? t("admin.users.noMatch") : t("admin.users.empty")}
          description={searching ? t("admin.users.noMatchHint") : t("admin.users.emptyHint")}
        />
      ) : (
        <>
          {/* The catalog failed, but the users did not. Saying so is the only
              honest option: the list below is correct except that it cannot name
              anybody's roles, and silently rendering "No roles" would be this
              screen asserting the opposite of what it knows. */}
          {rolesUnavailable && (
            <div
              role="alert"
              className="flex items-center flex-wrap bg-surface border border-line rounded-11"
              style={{
                gap: "var(--ob-space-11)",
                padding: "var(--ob-space-11) var(--ob-space-16)",
                marginBottom: "var(--ob-space-8)",
              }}
            >
              <span
                className="flex-1 min-w-0"
                style={{
                  color: "var(--ob-risk-fg)",
                  font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
                }}
              >
                {t("admin.users.roles.unavailable")}
              </span>
              <Button
                type="button"
                variant="secondary"
                onClick={() => void roles.refetch()}
                style={{ height: "var(--ob-control-height-sm)" }}
              >
                {t("common.retry")}
              </Button>
            </div>
          )}

          <ul className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
            {list.map((user) => (
              <UserRow
                key={user.id}
                user={user}
                roles={roles.data ?? []}
                canManage={canManage}
                canViewRoles={canViewRoles}
                rolesUnavailable={rolesUnavailable}
                onManageRoles={() => setManagingRolesFor(user)}
                onEdit={() => {
                  update.reset();
                  setEditing(user);
                }}
                onDeactivate={() => {
                  deactivate.reset();
                  setDeactivating(user);
                }}
              />
            ))}
          </ul>

          {totalPages > 1 && (
            <Pagination
              label={t("admin.users.page.nav")}
              page={page}
              totalPages={totalPages}
              onChange={setPage}
              // isFetching, not isLoading: the previous page stays on screen
              // while the next one loads, so without this a second click would
              // queue a page the user never sees the first of.
              disabled={users.isFetching}
            />
          )}
        </>
      )}

      {inviting && (
        <Dialog title={t("admin.users.invite")} onClose={() => setInviting(false)}>
          <InviteForm
            pending={invite.isPending}
            error={invite.isError ? t("common.error") : undefined}
            onCancel={() => setInviting(false)}
            onSubmit={(values) =>
              invite.mutate(values, { onSuccess: () => setInviting(false) })
            }
          />
        </Dialog>
      )}

      {/* `roles.data` is the guard as well as the source: RoleAssignment's empty
          state means "this tenant has no roles", and it must never be reachable
          by a fetch that failed. The trigger is already hidden in that case; this
          is the same statement where the dialog can see it. */}
      {managing && roles.data && (
        <Dialog
          title={t("admin.users.roles.for", { name: managing.fullName ?? "" })}
          onClose={() => setManagingRolesFor(null)}
        >
          <RoleAssignment user={managing} roles={roles.data} />
          <DialogActions>
            <Button type="button" variant="secondary" onClick={() => setManagingRolesFor(null)}>
              {t("common.close")}
            </Button>
          </DialogActions>
        </Dialog>
      )}

      {editing && (
        <Dialog title={t("admin.users.edit.title")} onClose={() => setEditing(null)}>
          <EditForm
            user={editing}
            pending={update.isPending}
            error={update.isError ? t("common.error") : undefined}
            onCancel={() => setEditing(null)}
            onSubmit={(values) =>
              update.mutate(
                { id: editing.id ?? "", body: values },
                { onSuccess: () => setEditing(null) },
              )
            }
          />
        </Dialog>
      )}

      {deactivating && (
        <Dialog title={t("admin.users.deactivate.title")} onClose={() => setDeactivating(null)}>
          <p
            className="text-text-muted"
            style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
          >
            {t("admin.users.deactivate.confirm", { name: deactivating.fullName ?? "" })}
          </p>
          {deactivate.isError && (
            <p
              role="alert"
              style={{
                color: "var(--ob-risk-fg)",
                marginTop: "var(--ob-space-11)",
                font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
              }}
            >
              {t("common.error")}
            </p>
          )}
          <DialogActions>
            <Button type="button" variant="secondary" onClick={() => setDeactivating(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              type="button"
              disabled={deactivate.isPending}
              onClick={() =>
                deactivate.mutate(deactivating.id ?? "", {
                  onSuccess: () => setDeactivating(null),
                })
              }
            >
              {t("common.confirm")}
            </Button>
          </DialogActions>
        </Dialog>
      )}
    </section>
  );
}

/**
 * One user, as a card row rather than a table.
 *
 * A table would need a roles column of unbounded width — a user can hold any
 * number of roles — and the design's table cells are fixed-share columns. The
 * two-line card list is the same shape the customer table falls back to below
 * 1024px, so it is a pattern the product already has.
 */
function UserRow({
  user,
  roles,
  canManage,
  canViewRoles,
  rolesUnavailable,
  onManageRoles,
  onEdit,
  onDeactivate,
}: {
  user: User;
  roles: Role[];
  canManage: boolean;
  canViewRoles: boolean;
  /** The catalog failed to load, so nothing here can name a role. */
  rolesUnavailable: boolean;
  onManageRoles: () => void;
  onEdit: () => void;
  onDeactivate: () => void;
}) {
  const name = user.fullName ?? "";
  const held = user.roleIds ?? [];
  const heldNames = held
    .map((id) => roles.find((role) => role.id === id)?.name)
    .filter((roleName): roleName is string => Boolean(roleName));

  return (
    <li
      className="bg-surface border border-line rounded-11"
      style={{ padding: "var(--ob-space-13) var(--ob-space-16)" }}
    >
      <div className="flex flex-wrap items-center" style={{ gap: "var(--ob-space-11)" }}>
        {/* Circular, because this is a person. A company is a rounded square. */}
        <Avatar name={name} kind="person" />

        <div className="flex-1 min-w-0">
          <p
            className="truncate text-ink"
            style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
          >
            {name}
          </p>
          {/* An address is a machine-readable identifier, so it is mono. */}
          <p
            className="truncate text-text-faint"
            style={{ font: "var(--ob-type-breadcrumb-size)/var(--ob-type-breadcrumb-line) var(--ob-font-family-data)" }}
          >
            {user.email}
          </p>
        </div>

        <div className="flex flex-wrap items-center" style={{ gap: "var(--ob-space-8)" }}>
          {canViewRoles &&
            (rolesUnavailable ? (
              // Not "No roles": that is a claim about this user, and the request
              // that would have supported it failed.
              <Quiet>{t("admin.users.roles.unknown")}</Quiet>
            ) : heldNames.length > 0 ? (
              heldNames.map((roleName) => <RoleChip key={roleName} name={roleName} />)
            ) : (
              <Quiet>{t("admin.users.noRoles")}</Quiet>
            ))}

          <StatusPill status={user.status} />

          {/* The only way a department can be changed after creation -- see
              CLAUDE.md's open item. Available regardless of status: renaming
              or re-departmenting a deactivated user is still a legitimate
              correction, unlike signing them back in. */}
          {canManage && (
            <Button
              type="button"
              variant="secondary"
              aria-label={t("admin.users.edit.for", { name })}
              onClick={onEdit}
              style={{ height: "var(--ob-control-height-sm)" }}
            >
              {t("admin.users.edit")}
            </Button>
          )}

          {/* Hidden while the catalog is unreachable: the dialog it opens lists
              roles, and it would open onto nothing. */}
          {canManage && canViewRoles && !rolesUnavailable && (
            <Button
              type="button"
              variant="secondary"
              // Every row's button would otherwise read "Manage roles", which is
              // indistinguishable to anyone navigating by control rather than eye.
              aria-label={t("admin.users.roles.for", { name })}
              onClick={onManageRoles}
              style={{ height: "var(--ob-control-height-sm)" }}
            >
              {t("admin.users.roles")}
            </Button>
          )}

          {/* Deactivation, never deletion — there is no delete on a user. */}
          {canManage && user.status !== "DEACTIVATED" && (
            <Button
              type="button"
              variant="secondary"
              aria-label={t("admin.users.deactivate.for", { name })}
              onClick={onDeactivate}
              style={{ height: "var(--ob-control-height-sm)" }}
            >
              {t("admin.users.deactivate")}
            </Button>
          )}
        </div>
      </div>
    </li>
  );
}

/**
 * A role's name, as its author wrote it.
 *
 * Deliberately NOT StatusPill, which was the first attempt and was wrong twice
 * over. StatusPill humanises what it is given — first character kept, the rest
 * lower-cased — so "Sales Representative" and "Account Manager" reached the DOM,
 * and the accessibility tree, as "Sales representative" and "Account manager".
 * It also renders in `font-family-data` and uppercases, and a tenant-authored
 * role name is human text: Archivo, as written, per the mono-is-for-machines
 * rule.
 *
 * Neutral, because a role is not a state, and a colour naming no state is
 * decoration. The e2e suite missed this because the only roles it asserted --
 * "Administrator" and "Support" -- are single words that survive humanise()
 * unchanged.
 */
function RoleChip({ name }: { name: string }) {
  return (
    <span
      className="bg-neutral-bg text-neutral-fg whitespace-nowrap"
      style={{
        borderRadius: "var(--ob-radius-5)",
        padding: "3px 9px",
        font: "500 var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
      }}
    >
      {name}
    </span>
  );
}

/** A muted statement where a chip would otherwise be. */
function Quiet({ children }: { children: ReactNode }) {
  return (
    <span
      className="text-text-subtle"
      style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
    >
      {children}
    </span>
  );
}

/**
 * Assign and unassign, one role at a time.
 *
 * Not a set of switches applied on save: each is its own gated, audited endpoint
 * call, and a switch that flips before the server agrees would show authority the
 * user does not have. PORTAL users are refused internal roles server-side, which
 * is why the failure message is shown rather than assumed away.
 */
function RoleAssignment({ user, roles }: { user: User; roles: Role[] }) {
  const assign = useAssignRole();
  const unassign = useUnassignRole();
  const held = new Set(user.roleIds ?? []);
  const userId = user.id ?? "";
  const failed = assign.isError || unassign.isError;

  if (roles.length === 0) {
    return (
      <p
        className="text-text-subtle"
        style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
      >
        {t("admin.roles.empty")}
      </p>
    );
  }

  return (
    <>
      <ul className="flex flex-col" style={{ gap: "var(--ob-space-2)", maxHeight: 320, overflowY: "auto" }}>
        {roles.map((role) => {
          const roleId = role.id ?? "";
          const assigned = held.has(roleId);
          const pending =
            (assign.isPending && assign.variables?.roleId === roleId) ||
            (unassign.isPending && unassign.variables?.roleId === roleId);

          return (
            <li
              key={roleId}
              className="flex items-center border-t border-line-faint first:border-t-0"
              style={{ gap: "var(--ob-space-11)", padding: "var(--ob-space-8) 0" }}
            >
              <span
                className="flex-1 min-w-0 truncate text-ink"
                style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
              >
                {role.name}
              </span>
              <Button
                type="button"
                variant="secondary"
                aria-label={
                  assigned
                    ? t("admin.users.roles.remove.for", { role: role.name ?? "" })
                    : t("admin.users.roles.assign.for", { role: role.name ?? "" })
                }
                disabled={pending}
                onClick={() => {
                  assign.reset();
                  unassign.reset();
                  if (assigned) unassign.mutate({ userId, roleId });
                  else assign.mutate({ userId, roleId });
                }}
                style={{ height: "var(--ob-control-height-sm)" }}
              >
                {assigned ? t("admin.users.roles.remove") : t("admin.users.roles.assign")}
              </Button>
            </li>
          );
        })}
      </ul>

      {failed && (
        <p
          role="alert"
          style={{
            color: "var(--ob-risk-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
          }}
        >
          {t("admin.users.roles.failed")}
        </p>
      )}
    </>
  );
}

/**
 * The two-tier department resolution Task 8's brief describes.
 *
 * `department.manage` is ALL-only, and no seeded template pairs it with a
 * narrower `user.manage` -- so a holder sees (and may choose from) the whole
 * tenant list; anyone else falls back to their own `departmentId`, the one
 * department a DEPARTMENT-scoped `user.manage` holder is guaranteed to
 * succeed with (`scoping/AppUserDescriptor.departmentScope` compares the
 * target's own `departmentId` against the actor's). Never offer a department
 * the actor cannot manage into -- an option that will 404 is worse than no
 * option at all.
 */
function useDepartmentScope() {
  const canManageDepartments = useHasPermission("department.manage");
  const departments = useDepartments(canManageDepartments);
  const { user } = useAuth();
  return {
    canManageDepartments,
    departments: departments.data ?? [],
    ownDepartmentId: user?.departmentId,
  };
}

/**
 * Department `<select>`, hand-rolled to match `admin/org/page.tsx`'s existing
 * team-creation field exactly -- same tokens, same real `<label htmlFor>` --
 * rather than inventing a new pattern or moving it onto the `Field` component,
 * which is a separately deferred inconsistency (CLAUDE.md).
 */
function DepartmentSelect({
  id,
  value,
  options,
  onChange,
}: {
  id: string;
  value: string;
  options: { id?: string; name?: string }[];
  onChange: (value: string) => void;
}) {
  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
      <label
        htmlFor={id}
        className="text-text-muted"
        style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
      >
        {t("admin.users.field.department")}
      </label>
      <select
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="bg-surface border border-line text-ink"
        style={{
          height: "var(--ob-control-height)",
          borderRadius: "var(--ob-radius-9)",
          padding: "0 var(--ob-space-11)",
          font: "13px/1.3 var(--ob-font-family-ui)",
        }}
      >
        <option value="">{t("admin.users.field.department.none")}</option>
        {options.map((department) => (
          <option key={department.id} value={department.id}>
            {department.name}
          </option>
        ))}
      </select>
    </div>
  );
}

function InviteForm({
  pending,
  error,
  onSubmit,
  onCancel,
}: {
  pending: boolean;
  error?: string;
  onSubmit: (values: { email: string; fullName: string; departmentId?: string }) => void;
  onCancel: () => void;
}) {
  const { canManageDepartments, departments, ownDepartmentId } = useDepartmentScope();
  const [email, setEmail] = useState("");
  const [fullName, setFullName] = useState("");
  const [departmentId, setDepartmentId] = useState("");
  const [errors, setErrors] = useState<{ email?: string; fullName?: string }>({});

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        const values = { email: email.trim(), fullName: fullName.trim() };
        const next: { email?: string; fullName?: string } = {};
        if (!values.email) next.email = t("customer.form.required");
        if (!values.fullName) next.fullName = t("customer.form.required");
        if (Object.keys(next).length > 0) {
          setErrors(next);
          return;
        }
        // A department.manage holder picks (or leaves blank) from the full
        // list; anyone else is silently given their own department -- the
        // only one they are guaranteed to succeed with -- with no field shown
        // to choose it (there is nothing else to choose).
        const resolvedDepartmentId = canManageDepartments ? departmentId || undefined : ownDepartmentId;
        onSubmit({ ...values, ...(resolvedDepartmentId ? { departmentId: resolvedDepartmentId } : {}) });
      }}
    >
      <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
        <Field
          label={t("admin.users.field.email")}
          type="email"
          value={email}
          error={errors.email}
          onChange={(event) => {
            setEmail(event.target.value);
            setErrors((previous) => ({ ...previous, email: undefined }));
          }}
        />
        <Field
          label={t("admin.users.field.fullName")}
          value={fullName}
          error={errors.fullName}
          onChange={(event) => {
            setFullName(event.target.value);
            setErrors((previous) => ({ ...previous, fullName: undefined }));
          }}
        />

        {canManageDepartments && departments.length > 0 && (
          <DepartmentSelect
            id="invite-department"
            value={departmentId}
            options={departments}
            onChange={setDepartmentId}
          />
        )}
      </div>

      <p
        className="text-text-subtle"
        style={{
          marginTop: "var(--ob-space-11)",
          font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
        }}
      >
        {t("admin.users.invite.hint")}
      </p>

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
          {t("admin.users.invite.submit")}
        </Button>
      </DialogActions>
    </form>
  );
}

/**
 * The only way to change a user's department after creation -- see CLAUDE.md's
 * open item. Full name is always editable; the department field follows the
 * same two-tier rule `InviteForm` does, EXCEPT a narrow-scoped actor's
 * fallback here is the record's OWN current departmentId rather than the
 * actor's -- they coincide (a DEPARTMENT-scoped `user.manage` holder can only
 * ever have reached this user if it is already in their department), and
 * resending it unchanged is required because `PUT` is a full replace: omitting
 * the field would blank it, not leave it alone.
 */
function EditForm({
  user,
  pending,
  error,
  onSubmit,
  onCancel,
}: {
  user: User;
  pending: boolean;
  error?: string;
  onSubmit: (values: { fullName: string; departmentId?: string }) => void;
  onCancel: () => void;
}) {
  const { canManageDepartments, departments } = useDepartmentScope();
  const [fullName, setFullName] = useState(user.fullName ?? "");
  const [departmentId, setDepartmentId] = useState(user.departmentId ?? "");
  const [fullNameError, setFullNameError] = useState<string>();

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        const trimmed = fullName.trim();
        if (!trimmed) {
          setFullNameError(t("customer.form.required"));
          return;
        }
        const resolvedDepartmentId = canManageDepartments ? departmentId || undefined : user.departmentId;
        onSubmit({ fullName: trimmed, ...(resolvedDepartmentId ? { departmentId: resolvedDepartmentId } : {}) });
      }}
    >
      <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
        <Field
          label={t("admin.users.field.fullName")}
          value={fullName}
          error={fullNameError}
          onChange={(event) => {
            setFullName(event.target.value);
            setFullNameError(undefined);
          }}
        />

        {canManageDepartments && departments.length > 0 && (
          <DepartmentSelect
            id="edit-department"
            value={departmentId}
            options={departments}
            onChange={setDepartmentId}
          />
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
