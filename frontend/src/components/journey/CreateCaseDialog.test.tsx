import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { CreateCaseDialog } from "./CreateCaseDialog";

afterEach(cleanup);

const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function makeWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

const templates = [
  { id: "t-1", name: "Standard Onboarding", currentVersionId: "v-1", currentVersionNo: 3 },
  { id: "t-2", name: "Fast Track", currentVersionId: undefined, currentVersionNo: undefined },
];

const definition = {
  versionId: "v-1",
  attributes: [
    { key: "industry", label: "Industry", dataType: "ENUM", required: true, allowedValues: ["Finance", "Retail"] },
    { key: "notes", label: "Notes", dataType: "STRING", required: false },
  ],
};

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockImplementation((url: string) => {
    if (url.includes("/workflows/t-1/versions/v-1")) return Promise.resolve(reply(definition));
    if (url.endsWith("/workflows")) return Promise.resolve(reply(templates));
    return Promise.resolve(reply({}));
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function renderDialog(onCreated = vi.fn(), onCancel = vi.fn()) {
  render(<CreateCaseDialog customerId="cust-1" onCreated={onCreated} onCancel={onCancel} />, {
    wrapper: makeWrapper(),
  });
}

describe("CreateCaseDialog", () => {
  it("offers only templates that have a published version", async () => {
    renderDialog();
    await waitFor(() => expect(screen.getByText("Standard Onboarding")).not.toBeNull());
    expect(screen.queryByText("Fast Track")).toBeNull();
  });

  it("renders a field per declared attribute with its label and allowed values", async () => {
    renderDialog();
    await waitFor(() => expect(screen.getByText("Standard Onboarding")).not.toBeNull());
    fireEvent.click(screen.getByText("Standard Onboarding"));

    await waitFor(() => expect(screen.getByLabelText(/Industry/)).not.toBeNull());
    const select = screen.getByLabelText(/Industry/) as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(expect.arrayContaining(["Finance", "Retail"]));
    expect(screen.getByLabelText(/Notes/)).not.toBeNull();
  });

  it("marks required attributes required", async () => {
    renderDialog();
    await waitFor(() => expect(screen.getByText("Standard Onboarding")).not.toBeNull());
    fireEvent.click(screen.getByText("Standard Onboarding"));

    await waitFor(() => expect(screen.getByText(/Industry/)).not.toBeNull());
    expect(screen.getByText("Industry *")).not.toBeNull();
    expect(screen.getByText("Notes")).not.toBeNull();
  });

  it("renders each 422 problem against its own field", async () => {
    renderDialog();
    await waitFor(() => expect(screen.getByText("Standard Onboarding")).not.toBeNull());
    fireEvent.click(screen.getByText("Standard Onboarding"));
    await waitFor(() => expect(screen.getByLabelText(/Industry/)).not.toBeNull());

    fetchMock.mockImplementationOnce(() =>
      Promise.resolve(reply({ problems: ["Attribute 'industry' is required"] }, 422)),
    );
    fireEvent.click(screen.getByRole("button", { name: /create case/i }));

    await waitFor(() => expect(screen.getByTestId("attribute-field-industry")).not.toBeNull());
    expect(screen.getByTestId("attribute-field-industry").textContent).toContain(
      "Attribute 'industry' is required",
    );
    expect(screen.getByTestId("attribute-field-notes").textContent).not.toContain("required");
  });
});
