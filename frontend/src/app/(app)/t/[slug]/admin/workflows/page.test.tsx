import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { WorkflowTemplate } from "@/lib/api/workflows";

let permissions: Record<string, string[]> = {};
const push = vi.fn();

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "acme" }),
  useRouter: () => ({ push, replace: vi.fn() }),
}));

const { default: WorkflowsPage } = await import("./page");

const fetchMock = vi.fn();

const template: WorkflowTemplate = {
  id: "tmpl-1",
  name: "Onboarding",
  description: "",
  status: "ACTIVE",
  currentVersionId: undefined,
  currentVersionNo: undefined,
};

function jsonReply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<WorkflowsPage />, { wrapper: Wrapper });
}

beforeEach(() => {
  permissions = { "workflow.view": ["ALL"], "workflow.manage": ["ALL"] };
  push.mockClear();
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("WorkflowsPage", () => {
  it("offers to resume or discard the already-open draft on a 409", async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST" && url.includes("/versions")) {
        return jsonReply(
          { type: "about:blank", title: "Conflict", status: 409,
            detail: "Template tmpl-1 already has an open draft", versionId: "open-draft-1" },
          409,
        );
      }
      return jsonReply([template]);
    });

    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Start editing" }));

    await waitFor(() => expect(screen.getByRole("alert")).not.toBeNull());
    expect(screen.getByRole("button", { name: "Resume editing" })).not.toBeNull();
    expect(screen.getByRole("button", { name: "Discard draft" })).not.toBeNull();
  });

  it("navigates straight to the open draft when Resume editing is clicked", async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST" && url.includes("/versions")) {
        return jsonReply(
          { detail: "Template tmpl-1 already has an open draft", versionId: "open-draft-1" },
          409,
        );
      }
      return jsonReply([template]);
    });

    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Start editing" }));
    fireEvent.click(await screen.findByRole("button", { name: "Resume editing" }));

    expect(push).toHaveBeenCalledWith("/t/acme/admin/workflows/tmpl-1/versions/open-draft-1");
  });

  it("discards the open draft and retries opening a fresh one", async () => {
    let createCalls = 0;
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST" && url.includes("/discard")) {
        return jsonReply(undefined, 204);
      }
      if (init?.method === "POST" && url.includes("/versions")) {
        createCalls += 1;
        if (createCalls === 1) {
          return jsonReply(
            { detail: "Template tmpl-1 already has an open draft", versionId: "open-draft-1" },
            409,
          );
        }
        return jsonReply({ versionId: "fresh-draft", templateId: "tmpl-1", status: "DRAFT" }, 201);
      }
      return jsonReply([template]);
    });

    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Start editing" }));
    fireEvent.click(await screen.findByRole("button", { name: "Discard draft" }));

    await waitFor(() =>
      expect(push).toHaveBeenCalledWith("/t/acme/admin/workflows/tmpl-1/versions/fresh-draft"),
    );
    expect(fetchMock.mock.calls.some(
      (call) => (call[1] as RequestInit | undefined)?.method === "POST"
        && (call[0] as string).includes("/workflows/tmpl-1/versions/open-draft-1/discard"),
    )).toBe(true);
  });
});
