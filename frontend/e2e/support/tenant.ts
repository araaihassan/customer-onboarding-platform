import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { expect } from "@playwright/test";
import type { APIRequestContext, Page } from "@playwright/test";

import { BASE_URL, PLATFORM_ADMIN } from "../../playwright.config";

/**
 * A request context with its own cookie jar, pointed at the frontend origin.
 *
 * Its own jar matters in `refresh-reuse.spec.ts`: replaying a retired token from
 * a context that also holds the live one would send both cookies and leave the
 * backend reading whichever came first.
 */
export function apiContext(playwright: {
  request: { newContext(options: { baseURL: string }): Promise<APIRequestContext> };
}) {
  return playwright.request.newContext({ baseURL: BASE_URL });
}

/**
 * Written by e2e/support/backend.mjs; the only place a token is readable.
 *
 * `__dirname` rather than `import.meta.url`: Playwright transpiles specs to
 * CommonJS, where `import.meta` is a syntax error at load time — the whole file
 * fails to parse, and the run reports "No tests found" rather than an error at the
 * line responsible.
 */
const BACKEND_LOG = join(__dirname, "..", ".artifacts", "backend.log");

/**
 * Long enough for the API's `@Size(min = 12)`, and the same for every seeded
 * account — these are throwaway tenants in a throwaway database.
 */
export const PASSWORD = "e2e-password-1234";

export type Tenant = {
  slug: string;
  tenantId: string;
  adminEmail: string;
};

/**
 * Slugs must not collide between parallel specs, and a run must not depend on the
 * database being empty. The timestamp keeps them readable when something fails and
 * the rows are still there.
 */
function uniqueSlug(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/**
 * Provisions a tenant and activates its administrator, which is the whole
 * bootstrap: the account is created INVITED with no password hash, `LoginService`
 * admits only ACTIVE, and only the activation invitation promotes one to the
 * other. A password reset is not a substitute — it sets the hash and deliberately
 * leaves `status` alone.
 *
 * Goes through :3000, not :8080, so the `next.config.ts` rewrite is exercised by
 * every spec that seeds anything.
 */
export async function provisionTenant(
  request: APIRequestContext,
  prefix: string,
): Promise<Tenant> {
  const slug = uniqueSlug(prefix);
  const adminEmail = `admin@${slug}.test`;

  const response = await request.post("/api/platform/tenants", {
    headers: {
      Authorization:
        "Basic " +
        Buffer.from(`${PLATFORM_ADMIN.email}:${PLATFORM_ADMIN.password}`).toString("base64"),
    },
    data: { slug, name: `Tenant ${slug}`, adminEmail, adminFullName: "Tenant Admin" },
  });
  expect(response.status(), await bodyOf(response)).toBe(200);
  const { tenantId } = (await response.json()) as { tenantId: string };

  const token = await readEmailToken(adminEmail);
  await activate(request, slug, token, PASSWORD);

  return { slug, tenantId, adminEmail };
}

export async function activate(
  request: APIRequestContext,
  slug: string,
  token: string,
  password: string,
) {
  const response = await request.post(`/api/t/${slug}/auth/activate`, {
    data: { token, password },
  });
  expect(response.status(), await bodyOf(response)).toBe(204);
}

/**
 * The most recent token emailed to an address, read out of the backend log.
 *
 * Deliberately the LAST match, not the first: a contact re-invited during a spec
 * has two tokens in the log, and the first one is the retired one.
 *
 * Polls, because logging is asynchronous — the HTTP response that triggered the
 * email can land before the appender has flushed the line.
 */
export async function readEmailToken(
  email: string,
  subjectContains = "",
  timeoutMs = 15_000,
): Promise<string> {
  const deadline = Date.now() + timeoutMs;
  const pattern = new RegExp(
    `\\[email\\] to=${escapeRegExp(email)} subject=([^\\n\\r]*${escapeRegExp(subjectContains)}[^\\n\\r]*)[\\r\\n]+[^\\n\\r]*: ([A-Za-z0-9_-]{20,})`,
    "g",
  );

  for (;;) {
    let log: string;
    try {
      log = await readFile(BACKEND_LOG, "utf8");
    } catch {
      throw new Error(
        `No backend log at ${BACKEND_LOG}. The backend must be started through ` +
          `e2e/support/backend.mjs — tokens are only readable from its output.`,
      );
    }

    const matches = [...log.matchAll(pattern)];
    const last = matches[matches.length - 1];
    if (last?.[2]) return last[2];

    if (Date.now() > deadline) {
      throw new Error(`No token emailed to ${email} appeared in ${BACKEND_LOG} within ${timeoutMs}ms`);
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
}

/** A bearer token, for seeding through the API rather than through the interface. */
export async function apiLogin(
  request: APIRequestContext,
  slug: string,
  email: string,
  password = PASSWORD,
): Promise<string> {
  const response = await request.post(`/api/t/${slug}/auth/login`, {
    data: { email, password },
  });
  expect(response.status(), await bodyOf(response)).toBe(200);
  const { accessToken } = (await response.json()) as { accessToken: string };
  return accessToken;
}

/**
 * Signs in through the interface and waits for the application shell.
 *
 * Through the form rather than by seeding a cookie: the login page is one of the
 * flows under test, and a helper that bypassed it would leave every other spec
 * depending on a path nothing exercises.
 */
export async function signIn(page: Page, slug: string, email: string, password = PASSWORD) {
  await page.goto(`/t/${slug}/login`);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL(`**/t/${slug}/dashboard`);
}

/**
 * The `/admin` and `/customers` surface, as an authenticated caller.
 *
 * Used for seeding, and — in `customers.spec.ts` — for the half of permission
 * gating that matters: calling the endpoint directly when the button is hidden.
 */
export class Api {
  constructor(
    private readonly request: APIRequestContext,
    private readonly slug: string,
    private readonly accessToken: string,
  ) {}

  static async as(request: APIRequestContext, slug: string, email: string, password = PASSWORD) {
    return new Api(request, slug, await apiLogin(request, slug, email, password));
  }

  private get headers() {
    return { Authorization: `Bearer ${this.accessToken}` };
  }

  async post<T>(path: string, data?: unknown, expected = 200): Promise<T> {
    const response = await this.request.post(`/api/t/${this.slug}${path}`, {
      headers: this.headers,
      ...(data === undefined ? {} : { data }),
    });
    expect(response.status(), await bodyOf(response)).toBe(expected);
    return expected === 204 ? (undefined as T) : ((await response.json()) as T);
  }

  async get<T>(path: string): Promise<T> {
    const response = await this.request.get(`/api/t/${this.slug}${path}`, {
      headers: this.headers,
    });
    expect(response.status(), await bodyOf(response)).toBe(200);
    return (await response.json()) as T;
  }

  async put<T>(path: string, data?: unknown, expected = 200): Promise<T> {
    const response = await this.request.put(`/api/t/${this.slug}${path}`, {
      headers: this.headers,
      ...(data === undefined ? {} : { data }),
    });
    expect(response.status(), await bodyOf(response)).toBe(expected);
    return (await response.json()) as T;
  }

  createCustomer(displayName: string) {
    return this.post<{ id: string }>(
      "/customers",
      { displayName, legalName: `${displayName} Ltd`, industry: "Software", country: "GB" },
      201,
    );
  }

  createContact(customerId: string, fullName: string, email: string) {
    return this.post<{ id: string }>(
      `/customers/${customerId}/contacts`,
      { fullName, email, primaryContact: true },
      201,
    );
  }

  sendInvitation(customerId: string, contactId: string) {
    return this.post<void>(`/customers/${customerId}/contacts/${contactId}/invitations`, undefined, 204);
  }

  createRole(name: string, grants: Record<string, string>) {
    return this.post<{ id: string }>("/admin/roles", { name, description: "", grants }, 201);
  }

  createWorkflowTemplate(name: string) {
    return this.post<{ id: string }>("/workflows", { name, description: "" }, 201);
  }

  createDraftVersion(templateId: string) {
    return this.post<{ versionId: string }>(`/workflows/${templateId}/versions`, undefined, 201);
  }

  saveDraft(templateId: string, versionId: string, body: unknown) {
    return this.put<{ versionId: string }>(`/workflows/${templateId}/versions/${versionId}`, body);
  }

  publishVersion(templateId: string, versionId: string) {
    return this.post<void>(`/workflows/${templateId}/versions/${versionId}/publish`, undefined, 200);
  }

  /**
   * The single-stage, single-milestone workflow every screen sweep needs a
   * real case against -- published, so the journey workspace has a roadmap
   * to render rather than the "no cases" empty state.
   */
  async publishMinimalWorkflow(name: string): Promise<{ templateId: string }> {
    const { id: templateId } = await this.createWorkflowTemplate(name);
    const { versionId } = await this.createDraftVersion(templateId);
    await this.saveDraft(templateId, versionId, {
      stages: [{ key: "stage-1", name: "Registration", milestones: [{ key: "milestone-1", name: "Registration" }] }],
    });
    await this.publishVersion(templateId, versionId);
    return { templateId };
  }

  createCase(customerId: string, templateId: string) {
    return this.post<{ id: string }>("/cases", { customerId, templateId }, 201);
  }

  createUser(email: string, fullName: string) {
    return this.post<{ id: string }>("/admin/users", { email, fullName }, 201);
  }

  assignRole(userId: string, roleId: string) {
    return this.post<void>(`/admin/users/${userId}/roles`, { roleId }, 204);
  }
}

/**
 * A colleague holding exactly the grants given, and nothing else — activated and
 * able to sign in.
 */
export async function seedUser(
  request: APIRequestContext,
  admin: Api,
  tenant: Tenant,
  name: string,
  grants: Record<string, string>,
): Promise<string> {
  const email = `${name}@${tenant.slug}.test`;
  const { id: userId } = await admin.createUser(email, name);
  const { id: roleId } = await admin.createRole(`${name}-role`, grants);
  await admin.assignRole(userId, roleId);

  await activate(request, tenant.slug, await readEmailToken(email), PASSWORD);
  return email;
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Failure messages that name the status AND the body, so a 400 says what was wrong. */
async function bodyOf(response: { text(): Promise<string> }): Promise<string> {
  return (await response.text()).slice(0, 500);
}
