"use client";

import { useAuth } from "./useAuth";
import type { Scope } from "./types";

/**
 * THIS HOOK CONTROLS UI AFFORDANCES ONLY. The server is the sole authority
 * (spec §10.3).
 *
 * Hiding a button the user cannot use is a courtesy, not a control: every endpoint
 * enforces independently, and Task 22's DirectApiAccessTest calls those endpoints
 * directly to prove it. Never treat a true from here as permission to skip a
 * server check, and never treat a false as a reason to omit one.
 */
export function useHasPermission(key: string, scope?: Scope): boolean {
  const { permissions } = useAuth();
  const scopes = permissions[key];

  if (!scopes || scopes.length === 0) return false;
  if (!scope) return true;

  // ALL subsumes every narrower scope, mirroring AuthorizationPredicateBuilder.
  return scopes.includes("ALL") || scopes.includes(scope);
}
