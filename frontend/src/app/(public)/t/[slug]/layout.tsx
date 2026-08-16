"use client";

import { use } from "react";
import { AuthProvider } from "@/lib/auth/AuthProvider";

/**
 * The public route group mounts AuthProvider but NOT AuthGuard.
 *
 * The provider is needed because login() lives on it and it configures the API
 * client's tenant slug; the guard is not, because these pages exist precisely for
 * people who are not signed in. Guarding them would redirect a signed-out user to
 * the login page from the login page.
 */
export default function PublicTenantLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ slug: string }>;
}) {
  const { slug } = use(params);
  return <AuthProvider slug={slug}>{children}</AuthProvider>;
}
