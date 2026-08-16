import { t } from "@/lib/i18n";

/**
 * Maps a failed login response to the message shown to the user.
 *
 * Extracted from the page so it can be tested directly, because the property it
 * carries is a security one rather than a cosmetic one.
 *
 * 401 is ONE message. The backend deliberately returns the same 401 for a wrong
 * password, an unknown address, and an inactive account, so that login cannot be
 * used to discover which addresses have accounts. Splitting that into "no such
 * user" and "wrong password" in the UI reintroduces exactly the oracle the shared
 * response exists to prevent — and it is a natural, well-meaning thing to do,
 * which is why it is pinned by a test.
 *
 * 429 is distinguishable and safe, because throttling applies to unknown
 * addresses too (Task 17): being locked out reveals nothing about existence.
 */
export function loginErrorMessage(status: number | undefined): string {
  if (status === 429) return t("auth.login.lockedOut");
  if (status === 401) return t("auth.login.error");
  return t("common.error");
}
