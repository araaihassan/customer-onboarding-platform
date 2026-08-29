"use client";

import { useEffect } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ComponentType } from "react";
import { LayoutDashboardIcon, SlidersIcon, UsersIcon } from "@/components/icons";
import type { IconProps } from "@/components/icons";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * The sidebar (component-specs §2): the second column of the two-column shell,
 * next to the icon rail (Rail.tsx, Task 3). Brand mark and account menu live on
 * the rail now, not here -- this component is nav items only.
 *
 * At and above 1024px it is always inline. Below 1024px it is a drawer: fully
 * hidden until toggled open (SCREENS.md's RESPONSIVE table), replacing the old
 * design's icon-only collapse at 1281px. `isOpen`/`onClose` are optional so a
 * caller that never needs the drawer behaviour (or a test rendering the
 * component in isolation) can omit them entirely and get the always-inline
 * rendering.
 *
 * Hiding an entry the user has no permission for is a courtesy, not a control --
 * see useHasPermission. Every one of these routes enforces server-side.
 */
type NavItem = {
  label: string;
  href: string;
  /** Route prefix that keeps this entry current; a record page is still its section. */
  section: string;
  Icon: ComponentType<IconProps>;
};

export function Sidebar({
  slug,
  isOpen,
  onClose,
}: {
  slug: string;
  isOpen?: boolean;
  onClose?: () => void;
}) {
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

  const isDrawer = onClose !== undefined;

  // Escape closes the drawer. Only attached when it's actually a controlled drawer.
  useEffect(() => {
    if (!isDrawer || !isOpen) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") onClose?.();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [isDrawer, isOpen, onClose]);

  return (
    <>
      {isDrawer && isOpen && (
        <div
          className="fixed inset-0 z-40"
          style={{ background: "var(--ob-scrim-drawer)" }}
          onClick={onClose}
          aria-hidden="true"
        />
      )}
      <aside
        // `max-lg:`/`lg:` are named variants, not arbitrary ones, and every class
        // here is a whole token with whitespace on both sides. Both properties
        // matter: Tailwind extracts class names from source text, so a token
        // written flush against a `${` interpolation boundary is never seen and
        // its rule is never generated. That is exactly what happened here --
        // `min-[1024px]:flex${` produced no CSS, the `hidden` beside it did, and
        // the sidebar was `display:none` at every width, so the application had
        // no navigation at all. Tailwind's `lg` is 1024px, the same breakpoint
        // this always meant.
        className={
          isDrawer
            ? `fixed inset-y-0 z-50 flex ${isOpen ? "" : "max-lg:-translate-x-full"}`
            : "flex"
        }
        style={{
          left: "var(--ob-rail-width)",
          width: "var(--ob-sidebar-width)",
          background: "var(--ob-canvas)",
          borderRight: "1px solid var(--ob-line)",
          flexDirection: "column",
          padding: "12px 10px 10px",
          // Tailwind v4's translate-* utilities set the standalone CSS `translate`
          // property, not `transform` -- this has to transition the same property
          // the max-[1023px]:-translate-x-full class above actually sets, or the
          // slide-in/out animation silently no-ops.
          transition: isDrawer ? `translate var(--ob-duration-slide) var(--ob-ease-default)` : undefined,
        }}
      >
        <nav
          aria-label={t("shell.nav.label")}
          aria-hidden={isDrawer && !isOpen ? true : undefined}
        >
          <ul className="flex flex-col" style={{ gap: "var(--ob-space-2)" }}>
            {items.map((item) => {
              const active = pathname === item.section || pathname.startsWith(`${item.section}/`);
              return (
                <li key={item.href}>
                  <NavLink item={item} active={active} onNavigate={isDrawer ? onClose : undefined} />
                </li>
              );
            })}
          </ul>
        </nav>
      </aside>
    </>
  );
}

function NavLink({
  item,
  active,
  onNavigate,
}: {
  item: NavItem;
  active: boolean;
  onNavigate?: () => void;
}) {
  const { Icon, href, label } = item;
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      onClick={onNavigate}
      className="flex items-center w-full hover:bg-surface-active"
      style={{
        gap: "var(--ob-space-9)",
        padding: "7px 9px",
        borderRadius: "var(--ob-radius-8)",
        font: `${active ? 500 : 400} var(--ob-type-nav-item-size)/var(--ob-type-nav-item-line) var(--ob-font-family-ui)`,
        color: "var(--ob-ink)",
        background: active ? "var(--ob-surface-active)" : "transparent",
      }}
    >
      <span aria-hidden="true" className="grid place-items-center" style={{ flex: "0 0 14px", width: 14, color: "var(--ob-text-subtle)" }}>
        <Icon size={14} />
      </span>
      <span className="text-left">{label}</span>
    </Link>
  );
}
