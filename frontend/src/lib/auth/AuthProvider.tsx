"use client";

import { createContext, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { apiFetch, setTenantSlug, __setAccessToken } from "@/lib/api/client";
import type { AuthState, Me } from "./types";

export const AuthContext = createContext<AuthState | null>(null);

type LoginResponse = { accessToken: string; expiresInSeconds: number };

export function AuthProvider({ slug, children }: { slug: string; children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  // The client is a module singleton, so the slug must be set before any request
  // and re-set if the user navigates between tenants.
  setTenantSlug(slug);

  const loadMe = useCallback(async () => {
    const me = await apiFetch<Me>("/me");
    setUser(me);
  }, []);

  const startedRef = useRef(false);

  useEffect(() => {
    // React 18+ mounts effects twice in development StrictMode. A second silent
    // refresh would rotate the refresh token again, and the backend reads a
    // replayed token as theft — so this must run exactly once.
    if (startedRef.current) return;
    startedRef.current = true;

    // Silent refresh on mount: the in-memory token did not survive the reload but
    // the HttpOnly cookie did, so a page refresh should not sign the user out.
    // apiFetch("/me") 401s, refreshes, and retries in one step.
    loadMe()
      .catch(() => setUser(null))
      .finally(() => setIsLoading(false));
  }, [loadMe]);

  const login = useCallback(
    async (email: string, password: string) => {
      const response = await apiFetch<LoginResponse>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      });
      __setAccessToken(response.accessToken);
      await loadMe();
    },
    [loadMe],
  );

  const logout = useCallback(async () => {
    try {
      // Ends the whole refresh family server-side, not just this tab.
      await apiFetch<void>("/auth/logout", { method: "POST" });
    } finally {
      // Cleared even if the call fails — a client that thinks it is signed in
      // while holding a dead token is worse than one that simply signs out.
      __setAccessToken(null);
      setUser(null);
    }
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      user,
      permissions: user?.permissions ?? {},
      isLoading,
      login,
      logout,
    }),
    [user, isLoading, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
