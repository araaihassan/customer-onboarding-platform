"use client";

import { use } from "react";
import { AuthProvider } from "@/lib/auth/AuthProvider";
import { AuthGuard } from "@/lib/auth/AuthGuard";

/**
 * Placed at (app)/t/[slug]/ rather than the plan's (app)/layout.tsx, because the
 * guard needs the tenant slug and only a layout inside the [slug] segment receives
 * it. Every authenticated route lives under here, so this is still one guard for
 * the whole application.
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
      <AuthGuard slug={slug}>{children}</AuthGuard>
    </AuthProvider>
  );
}
