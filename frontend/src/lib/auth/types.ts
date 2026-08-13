import type { components } from "@/lib/api/generated";

/** Straight from the generated OpenAPI types, so a backend change is a compile error. */
export type Me = components["schemas"]["Me"];

/**
 * The four scopes, exactly (spec §6.3). Adding a fifth is a change to every
 * resource descriptor in the system, not a local addition here.
 */
export type Scope = "ALL" | "DEPARTMENT" | "TEAM" | "ASSIGNED";

export type AuthState = {
  user: Me | null;
  /** permission key → the scopes it is held at. Empty object when signed out. */
  permissions: Record<string, string[]>;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};
