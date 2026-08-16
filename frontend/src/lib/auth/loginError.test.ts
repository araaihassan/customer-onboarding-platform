import { describe, expect, it } from "vitest";
import { loginErrorMessage } from "./loginError";
import { t } from "@/lib/i18n";

describe("loginErrorMessage", () => {
  /**
   * The security property. The backend answers one 401 for wrong password, unknown
   * address and inactive account alike, so the UI must too — differentiating them
   * turns login into an account-existence oracle, which is exactly what the shared
   * response prevents. This is pinned because splitting the message is a natural,
   * well-meaning improvement someone will eventually reach for.
   */
  it("gives one indistinguishable message for every 401 cause", () => {
    expect(loginErrorMessage(401)).toBe(t("auth.login.error"));
    expect(loginErrorMessage(401)).not.toMatch(/unknown|no such|not found|inactive|exists/i);
  });

  /** Safe to distinguish: throttling applies to unknown addresses too (Task 17). */
  it("distinguishes lockout, which reveals nothing about existence", () => {
    expect(loginErrorMessage(429)).toBe(t("auth.login.lockedOut"));
    expect(loginErrorMessage(429)).not.toBe(loginErrorMessage(401));
  });

  it("falls back to a generic message for anything else", () => {
    expect(loginErrorMessage(500)).toBe(t("common.error"));
    expect(loginErrorMessage(undefined)).toBe(t("common.error"));
  });

  /** Never a raw status or a server string — those leak implementation detail. */
  it("never surfaces a status code or server text", () => {
    for (const status of [400, 401, 403, 429, 500, 502]) {
      expect(loginErrorMessage(status)).not.toMatch(/\d{3}/);
    }
  });
});
