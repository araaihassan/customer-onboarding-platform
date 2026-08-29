"use client";

import { use, useState } from "react";
import { AuthProvider } from "@/lib/auth/AuthProvider";
import { AuthGuard } from "@/lib/auth/AuthGuard";
import { PageHeaderProvider } from "@/components/shell/PageHeader";
import { QueryProvider } from "@/lib/api/QueryProvider";
import { Rail } from "@/components/shell/Rail";
import { Sidebar } from "@/components/shell/Sidebar";
import { TopBar } from "@/components/shell/TopBar";
import { ToastProvider } from "@/components/ui/Toast";

/**
 * Placed at (app)/t/[slug]/ rather than the plan's (app)/layout.tsx, because the
 * guard needs the tenant slug and only a layout inside the [slug] segment receives
 * it. Every authenticated route lives under here, so this is still one guard for
 * the whole application.
 *
 * The shell sits INSIDE AuthGuard, not around it: the rail's contents are derived
 * from the signed-in user's permissions, so rendering it for a signed-out visitor
 * would flash an empty frame on the way to login.
 */
export default function TenantLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ slug: string }>;
}) {
  // Next 15 delivers params as a promise to layouts; `use` unwraps it.
  const { slug } = use(params);
  // Drives the <1024px Sidebar drawer only (SCREENS.md's RESPONSIVE table) --
  // Sidebar renders inline at 1024px and above regardless of this value.
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <AuthProvider slug={slug}>
      <AuthGuard slug={slug}>
        {/* Inside the guard, so the cache is created when a user signs in and
            discarded when they sign out. Above it, a cache holding one user's
            records would survive into the next user's session. */}
        <QueryProvider>
          <PageHeaderProvider>
            <ToastProvider>
              <div className="flex min-h-screen">
                <Rail onToggleSidebar={() => setSidebarOpen((open) => !open)} />
                <Sidebar slug={slug} isOpen={sidebarOpen} onClose={() => setSidebarOpen(false)} />
                {/* Sidebar renders `position: fixed` (Task 4), so it takes no space
                    of its own in this flex row -- this spacer reserves its width
                    only at the breakpoint where Sidebar is actually inline
                    (`lg`, i.e. >=1024px), spelled the same way Sidebar spells it so
                    the two cannot drift apart,
                    so the content column starts to its right instead of underneath
                    it. Below 1024px Sidebar is a hidden or overlay drawer, so no
                    space is reserved and content runs from the rail's edge. */}
                <div
                  className="hidden lg:block shrink-0"
                  style={{ width: "var(--ob-sidebar-width)" }}
                  aria-hidden="true"
                />
                {/* The column is a plain div, not <main>. TopBar renders a <header>,
                    which is only the `banner` landmark while it is not inside <main>
                    — nesting it would leave the authenticated application with no
                    banner at all and put the global controls inside the main content
                    region on every screen.

                    min-w-0 is load-bearing: without it a long mono string in a table
                    cell sets the column's minimum content width and blows out the
                    grid instead of ellipsising. */}
                <div className="flex flex-1 min-w-0 flex-col">
                  <TopBar />
                  <main
                    className="flex-1"
                    style={{
                      paddingLeft: "var(--ob-content-padding-x)",
                      paddingRight: "var(--ob-content-padding-x)",
                      paddingTop: "var(--ob-content-padding-top)",
                      // 56px, so the last table row clears the fold rather than
                      // looking truncated.
                      paddingBottom: "var(--ob-content-padding-bottom)",
                    }}
                  >
                    {children}
                  </main>
                </div>
              </div>
            </ToastProvider>
          </PageHeaderProvider>
        </QueryProvider>
      </AuthGuard>
    </AuthProvider>
  );
}
