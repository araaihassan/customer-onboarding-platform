"use client";

import Link from "next/link";
import { usePathname, useParams } from "next/navigation";
import type { ReactNode } from "react";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * Navigation between the three administration screens.
 *
 * Styled as the design's tabs (component-specs §9) but built as a `<nav>` of
 * links, not `role="tablist"`. These change the route rather than swapping a panel
 * in the same document: a tablist would promise arrow-key movement between panels
 * that are actually separate pages, and would take the links out of the
 * link-listing every screen reader offers.
 *
 * Without this the org screen has no route into it from anywhere in the
 * interface. The rail's single Administration entry lands on users or roles; a
 * screen reachable only by typing its URL is a screen that exists for the tests.
 */
export default function AdminLayout({ children }: { children: ReactNode }) {
  const { slug } = useParams<{ slug: string }>();
  const pathname = usePathname();

  // Fixed hook order: every check runs on every render and the results are
  // filtered afterwards. Calling these inside a filter would be a hooks violation.
  const canViewUsers = useHasPermission("user.view");
  const canViewRoles = useHasPermission("role.view");
  const canManageDepartments = useHasPermission("department.manage");
  const canManageTeams = useHasPermission("team.manage");
  const canViewWorkflows = useHasPermission("workflow.view");

  const tabs: { label: string; href: string }[] = [];
  if (canViewUsers) tabs.push({ label: t("admin.users.title"), href: `/t/${slug}/admin/users` });
  if (canViewRoles) tabs.push({ label: t("admin.roles.title"), href: `/t/${slug}/admin/roles` });
  if (canManageDepartments || canManageTeams) {
    tabs.push({ label: t("admin.org.title"), href: `/t/${slug}/admin/org` });
  }
  if (canViewWorkflows) tabs.push({ label: t("workflow.list.title"), href: `/t/${slug}/admin/workflows` });

  return (
    <>
      {/* One entry is not a choice, so the strip is omitted rather than rendered
          as a single tab that goes where the user already is. */}
      {tabs.length > 1 && (
        <nav
          aria-label={t("admin.nav.label")}
          className="flex border-b border-line"
          style={{ marginBottom: "var(--ob-space-20)" }}
        >
          {tabs.map((tab) => {
            const active = pathname === tab.href || pathname.startsWith(`${tab.href}/`);
            return (
              <Link
                key={tab.href}
                href={tab.href}
                aria-current={active ? "page" : undefined}
                className={active ? "text-ink" : "text-text-subtle"}
                style={{
                  padding: "9px 15px 11px",
                  font: `${active ? 600 : 400} var(--ob-type-nav-item-size)/var(--ob-type-nav-item-line) var(--ob-font-family-ui)`,
                  // The 2px accent underline is the design's active treatment;
                  // the weight change carries it too, so it is not colour alone.
                  boxShadow: active ? "inset 0 -2px 0 var(--ob-accent-fg)" : undefined,
                }}
              >
                {tab.label}
              </Link>
            );
          })}
        </nav>
      )}
      {children}
    </>
  );
}
