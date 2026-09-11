import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { WorkflowTemplate } from "@/lib/api/workflows";
import { CloneTemplateDialog } from "../CloneTemplateDialog";

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

const fetchMock = vi.fn();

const catalogueTemplate: WorkflowTemplate = {
  id: "tmpl-1",
  name: "Standard Onboarding",
  description: "",
  status: "ACTIVE",
  currentVersionId: "v-1",
  currentVersionNo: 3,
};

const customers = [
  { id: "cust-1", displayName: "Acme Corp" },
  { id: "cust-2", displayName: "Acme Studios" },
];

function jsonReply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  permissions = { "customer.view": ["ALL"], "workflow.manage": ["ALL"] };
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string) => {
    if (url.includes("/customers")) return jsonReply({ content: customers });
    return jsonReply({});
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("CloneTemplateDialog", () => {
  it("renders nothing when closed", () => {
    const { container } = render(
      <CloneTemplateDialog template={catalogueTemplate} open={false} onClose={vi.fn()} />,
      { wrapper: makeWrapper() },
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("names the source template being cloned", () => {
    render(<CloneTemplateDialog template={catalogueTemplate} open onClose={vi.fn()} />, { wrapper: makeWrapper() });
    expect(screen.getByRole("dialog", { name: /Standard Onboarding/ })).toBeInTheDocument();
  });

  it("requires both a name and a customer before submitting", async () => {
    render(<CloneTemplateDialog template={catalogueTemplate} open onClose={vi.fn()} />, { wrapper: makeWrapper() });

    fireEvent.click(screen.getByRole("button", { name: "Clone template" }));

    expect(screen.getAllByText("This field is required").length).toBe(2);
    expect(fetchMock.mock.calls.some((c) => (c[1] as RequestInit | undefined)?.method === "POST")).toBe(false);
  });

  it("clones the template for the selected customer with a trimmed name", async () => {
    const onClose = vi.fn();
    render(<CloneTemplateDialog template={catalogueTemplate} open onClose={onClose} />, { wrapper: makeWrapper() });

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "  Acme Onboarding  " } });
    await screen.findByRole("option", { name: "Acme Corp" });
    fireEvent.change(screen.getByLabelText("Customer"), { target: { value: "cust-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Clone template" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const call = fetchMock.mock.calls.find((c) => (c[1] as RequestInit | undefined)?.method === "POST")!;
    expect(call[0]).toBe("/api/t/acme/workflows/tmpl-1/clone");
    expect(JSON.parse((call[1] as RequestInit).body as string)).toEqual({
      customerId: "cust-1",
      name: "Acme Onboarding",
    });
  });

  it("reports a duplicate clone rather than a generic error", async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes("/customers")) return jsonReply({ content: customers });
      if (init?.method === "POST") return jsonReply({ detail: "Already cloned" }, 409);
      return jsonReply({});
    });

    render(<CloneTemplateDialog template={catalogueTemplate} open onClose={vi.fn()} />, { wrapper: makeWrapper() });

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Acme Onboarding" } });
    await screen.findByRole("option", { name: "Acme Corp" });
    fireEvent.change(screen.getByLabelText("Customer"), { target: { value: "cust-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Clone template" }));

    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toBe("This customer already has a clone of this template."),
    );
  });

  it("offers no customer picker for someone without customer.view", () => {
    permissions = { "workflow.manage": ["ALL"] };
    render(<CloneTemplateDialog template={catalogueTemplate} open onClose={vi.fn()} />, { wrapper: makeWrapper() });

    expect(screen.queryByLabelText("Customer")).toBeNull();
    expect(screen.getByText("You do not have permission to search customers.")).toBeInTheDocument();
  });
});
