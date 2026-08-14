"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ComponentType } from "react";
import { LayoutDashboardIcon, SlidersIcon, UsersIcon } from "@/components/icons";
import type { IconProps } from "@/components/icons";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * The navigation rail (component-specs §1).
 *
 * The rail is the one surface identical in both themes — it is already dark in
 * light mode — which is what keeps navigation stable when the theme flips. Do not
 * "fix" it to follow bg-surface.
 *
 * Below 1281px it collapses to an icon-only 56px rail (review finding 11). The
 * boundary is not arbitrary: the design's content column is 1440 − 244 = 1196px,
 * and a 56px rail on a 1280px viewport leaves 1224px, so every screen built for
 * the design's own width still fits without reflowing. Labels stay in the DOM as
 * sr-only text, so the accessible name of each link is unchanged when collapsed.
 *
 * Hiding an entry the user has no permission for is a courtesy, not a control —
 * see useHasPermission. Every one of these routes enforces server-side.
 */
type NavItem = {
  label: string;
  href: string;
  /** Route prefix that keeps this entry current; a record page is still its section. */
  section: string;
  Icon: ComponentType<IconProps>;
};

export function Sidebar({ slug }: { slug: string }) {
  const pathname = usePathname();

  // Fixed hook order: every check runs on every render, and the results are
  // filtered afterwards. Calling these inside a filter would be a hooks violation.
  const canViewCustomers = useHasPermission("customer.view");
  const canViewUsers = useHasPermission("user.view");
  const canViewRoles = useHasPermission("role.view");

  const items: NavItem[] = [
    {
      label: t("nav.dashboard"),
      href: `/t/${slug}/dashboard`,
      section: `/t/${slug}/dashboard`,
      Icon: LayoutDashboardIcon,
    },
  ];

  if (canViewCustomers) {
    items.push({
      label: t("nav.customers"),
      href: `/t/${slug}/customers`,
      section: `/t/${slug}/customers`,
      Icon: UsersIcon,
    });
  }

  if (canViewUsers || canViewRoles) {
    items.push({
      label: t("nav.admin"),
      // Administration opens on the screen the user can actually read. Holding
      // role.view alone and landing on /admin/users would be a 404 by design —
      // out-of-scope records never announce themselves as forbidden.
      href: canViewUsers ? `/t/${slug}/admin/users` : `/t/${slug}/admin/roles`,
      section: `/t/${slug}/admin`,
      Icon: SlidersIcon,
    });
  }

  return (
    <aside
      className="sticky top-0 h-screen shrink-0 flex flex-col bg-bg-rail text-text-on-rail w-[var(--ob-rail-width-collapsed)] min-[1281px]:w-[var(--ob-rail-width)]"
    >
      <div
        className="flex items-center justify-center min-[1281px]:justify-start px-0 min-[1281px]:px-[var(--ob-space-20)]"
        style={{
          paddingTop: "var(--ob-space-22)",
          paddingBottom: "var(--ob-space-18)",
          gap: "var(--ob-space-10)",
        }}
      >
        <BrandMark />
        <div className="sr-only min-[1281px]:not-sr-only min-[1281px]:flex flex-col" style={{ lineHeight: 1.15 }}>
          <span
            style={{
              font: "600 var(--ob-type-14-size)/1.15 var(--ob-font-family-ui)",
              letterSpacing: "var(--ob-type-14-tracking)",
            }}
          >
            {t("shell.brand")}
          </span>
          {/* A slug is a machine value, so it is mono. 50% over the rail measures
              4.7:1 — the design's own figure, and it clears AA. */}
          <span
            style={{
              font: "var(--ob-type-10-size)/1.15 var(--ob-font-family-data)",
              opacity: 0.5,
            }}
          >
            {slug}
          </span>
        </div>
      </div>

      <nav aria-label={t("shell.nav.label")} style={{ padding: "var(--ob-space-6) var(--ob-space-10)" }}>
        <ul className="flex flex-col" style={{ gap: "var(--ob-space-2)" }}>
          {items.map((item) => {
            const active = pathname === item.section || pathname.startsWith(`${item.section}/`);
            return (
              <li key={item.href}>
                <NavLink item={item} active={active} />
              </li>
            );
          })}
        </ul>
      </nav>
    </aside>
  );
}

function NavLink({ item, active }: { item: NavItem; active: boolean }) {
  const { Icon, href, label } = item;

  // State-dependent background and opacity are classes rather than inline styles
  // on purpose: an inline style wins over any :hover rule, so the hover state
  // would silently never appear.
  const state = active
    ? "bg-bg-rail-raised opacity-100"
    : "opacity-[0.72] hover:opacity-100 hover:bg-[var(--ob-rail-item-hover)]";

  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      className={`flex items-center w-full justify-center min-[1281px]:justify-start ${state}`}
      style={{
        padding: "var(--ob-space-8) var(--ob-space-11)",
        borderRadius: "var(--ob-rail-item-radius)",
        gap: "var(--ob-space-10)",
        font: `${active ? 500 : 400} var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)`,
        color: "inherit",
      }}
    >
      <span
        aria-hidden="true"
        className="grid place-items-center"
        style={{ flex: "0 0 16px", width: 16, height: 16, opacity: 0.85 }}
      >
        <Icon size={16} />
      </span>
      <span className="sr-only min-[1281px]:not-sr-only min-[1281px]:flex-1 text-left">{label}</span>
    </Link>
  );
}

/**
 * The brand mark from 01-brand/logo/logo-tile.svg, inlined at 26px.
 *
 * Inlined rather than fetched so the rail has no network dependency, and with the
 * tile fill pointing at `accent` rather than the file's baked #4f5cc3 — that hex
 * IS indigo-600, and a mark that drifts from the accent when the palette moves is
 * exactly the kind of quiet erosion the design warns about.
 */
function BrandMark() {
  return (
    <svg
      width="26"
      height="26"
      viewBox="0 0 32 32"
      style={{ flex: "0 0 26px" }}
      aria-hidden="true"
      focusable="false"
    >
      <rect width="32" height="32" rx="9" fill="var(--ob-accent)" />
      <path
        d="M19.74 9.52A7.49 7.49 0 1 1 12.26 9.52"
        fill="none"
        stroke="#fff"
        strokeWidth="3.01"
        strokeLinecap="round"
      />
      <circle cx="16" cy="8.51" r="1.5" fill="#fff" />
    </svg>
  );
}
