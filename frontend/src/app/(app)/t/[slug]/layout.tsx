"use client";

import { use } from "react";
import { AuthProvider } from "@/lib/auth/AuthProvider";
import { AuthGuard } from "@/lib/auth/AuthGuard";
import { PageHeaderProvider } from "@/components/shell/PageHeader";
import { Sidebar } from "@/components/shell/Sidebar";
import { TopBar } from "@/components/shell/TopBar";

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

  return (
    <AuthProvider slug={slug}>
      <AuthGuard slug={slug}>
        <PageHeaderProvider>
          <div className="flex min-h-screen">
            <Sidebar slug={slug} />
            {/* min-w-0 is load-bearing: without it a long mono string in a table
                cell sets the column's minimum content width and blows out the
                grid instead of ellipsising. */}
            <main className="flex flex-1 min-w-0 flex-col">
              <TopBar />
              <div
                className="flex-1 px-[var(--ob-space-16)] md:px-[var(--ob-content-padding-x)]"
                style={{
                  paddingTop: "var(--ob-content-padding-top)",
                  // 56px, so the last table row clears the fold rather than
                  // looking truncated.
                  paddingBottom: "var(--ob-content-padding-bottom)",
                }}
              >
                {children}
              </div>
            </main>
          </div>
        </PageHeaderProvider>
      </AuthGuard>
    </AuthProvider>
  );
}
