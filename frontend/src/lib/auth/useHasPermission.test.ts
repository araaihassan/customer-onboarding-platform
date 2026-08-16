import { describe, expect, it, vi } from "vitest";
import type { Scope } from "./types";

/**
 * The hook is a pure function of the permission map, so it is tested through a
 * stubbed useAuth rather than by rendering. What matters here is the scope
 * algebra, and getting it wrong hides affordances a user is entitled to — or
 * shows ones they are not, which then fail at the server with a confusing 403.
 */
vi.mock("./useAuth", () => ({
  useAuth: () => ({ permissions: permissionsUnderTest }),
}));

let permissionsUnderTest: Record<string, string[]> = {};

const { useHasPermission } = await import("./useHasPermission");

// Named as a hook because it calls one — rules-of-hooks is right to insist, and
// renaming is the honest fix rather than disabling the rule in a test.
function useCheck(permissions: Record<string, string[]>, key: string, scope?: Scope) {
  permissionsUnderTest = permissions;
  return useHasPermission(key, scope);
}

describe("useHasPermission", () => {
  it("is false for a permission that is not held", () => {
    expect(useCheck({}, "customer.view")).toBe(false);
  });

  it("is false for a permission held at no scopes", () => {
    expect(useCheck({ "customer.view": [] }, "customer.view")).toBe(false);
  });

  it("is true when the permission is held at any scope and none is required", () => {
    expect(useCheck({ "customer.view": ["ASSIGNED"] }, "customer.view")).toBe(true);
  });

  it("matches the exact scope", () => {
    expect(useCheck({ "customer.view": ["TEAM"] }, "customer.view", "TEAM")).toBe(true);
  });

  it("is false when the required scope is not among those held", () => {
    expect(useCheck({ "customer.view": ["ASSIGNED"] }, "customer.view", "TEAM")).toBe(false);
  });

  /** ALL subsumes every narrower scope, mirroring AuthorizationPredicateBuilder. */
  it("treats ALL as satisfying any narrower scope", () => {
    expect(useCheck({ "customer.view": ["ALL"] }, "customer.view", "ASSIGNED")).toBe(true);
    expect(useCheck({ "customer.view": ["ALL"] }, "customer.view", "DEPARTMENT")).toBe(true);
  });

  /**
   * Scopes union across roles, so holding one narrow scope must not mask another.
   * This is the frontend mirror of MultipleRolesTest.
   */
  it("matches any scope in a unioned set", () => {
    const held = { "customer.view": ["TEAM", "ASSIGNED"] };
    expect(useCheck(held, "customer.view", "TEAM")).toBe(true);
    expect(useCheck(held, "customer.view", "ASSIGNED")).toBe(true);
    expect(useCheck(held, "customer.view", "DEPARTMENT")).toBe(false);
  });
});
