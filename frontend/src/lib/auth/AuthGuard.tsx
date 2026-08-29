"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import type { ReactNode } from "react";
import { useAuth } from "./useAuth";
import { SkeletonRows } from "@/components/ui/States";

/**
 * Renders nothing but a skeleton until the silent refresh resolves, then either
 * shows the application or redirects to login.
 *
 * The loading state is not cosmetic. Without it, a reload would flash the login
 * page for as long as the refresh takes — and worse, an eager redirect would
 * cancel the refresh and sign out a user whose session was perfectly valid.
 *
 * This is a convenience, not a control. Every endpoint enforces independently;
 * a user who defeats this guard reaches an API that refuses them anyway.
 */
export function AuthGuard({ slug, children }: { slug: string; children: ReactNode }) {
  const { user, isLoading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!isLoading && !user) router.replace(`/t/${slug}/login`);
  }, [isLoading, user, router, slug]);

  if (isLoading) {
    return (
      <div style={{ padding: "var(--ob-space-26)" }}>
        <SkeletonRows rows={5} />
      </div>
    );
  }

  // Redirect is in flight; rendering children here would briefly show the
  // application to someone who is not signed in.
  if (!user) return null;

  return <>{children}</>;
}
