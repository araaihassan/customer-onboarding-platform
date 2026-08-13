import { describe, expect, it } from "vitest";
import { t } from "./index";

describe("t", () => {
  it("resolves a known key", () => {
    expect(t("auth.login.title")).toBe("Sign in");
  });

  it("interpolates parameters", () => {
    expect(t("customer.deactivate.confirm", { name: "Acme" })).toContain("Acme");
  });

  it("returns the key itself when missing, so gaps are visible rather than silent", () => {
    expect(t("does.not.exist")).toBe("does.not.exist");
  });

  /**
   * Not in the plan. A placeholder left unreplaced is a visible bug in the UI, but
   * one that only appears for the caller who forgot the parameter — so it is worth
   * pinning that the template is returned verbatim rather than half-substituted.
   */
  it("leaves an unsupplied placeholder intact rather than printing undefined", () => {
    expect(t("customer.deactivate.confirm")).toContain("{name}");
    expect(t("customer.deactivate.confirm")).not.toContain("undefined");
  });

  /**
   * Not in the plan, and the reason this layer exists at all: every user-facing
   * string must be reachable by key. A missing key silently renders the key itself,
   * which is exactly the failure this asserts against for the strings the first
   * screens need.
   */
  it("has the keys the public pages and shell require", () => {
    const required = [
      "auth.login.title",
      "auth.login.error",
      "auth.login.lockedOut",
      "auth.activate.title",
      "auth.reset.title",
      "nav.dashboard",
      "nav.customers",
      "nav.admin",
      "customer.list.title",
      "customer.list.empty",
      "common.save",
      "common.cancel",
    ];
    for (const key of required) {
      expect(t(key), `missing translation for ${key}`).not.toBe(key);
    }
  });
});
