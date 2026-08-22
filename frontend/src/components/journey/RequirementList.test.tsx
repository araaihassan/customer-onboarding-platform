import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { RequirementRoadmap } from "@/lib/api/cases";
import { RequirementList } from "./RequirementList";

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

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

const open: RequirementRoadmap = { id: "r-1", label: "Collect ID", kind: "MANUAL", mandatory: true, status: "OPEN" };
const document: RequirementRoadmap = { id: "r-2", label: "Passport scan", kind: "DOCUMENT", mandatory: true, status: "OPEN" };

function renderList(requirements: RequirementRoadmap[]) {
  return render(<RequirementList caseId="c-1" milestoneId="m-1" requirements={requirements} />, {
    wrapper: makeWrapper(),
  });
}

beforeEach(() => {
  permissions = { "milestone.complete": ["ALL"], "requirement.waive": ["ALL"] };
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(reply({ id: "r-1", status: "SATISFIED" }));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("RequirementList", () => {
  /**
   * The one deliberate departure from prototype behaviour. uispecs says
   * checkbox state is "real and local"; here it cannot be -- satisfying a
   * requirement recomputes the milestone, possibly the stage transition and
   * the case percentage, all server-side.
   */
  it("waits for the server rather than flipping locally", async () => {
    let resolveSatisfy!: (value: Response) => void;
    fetchMock.mockReturnValueOnce(new Promise((resolve) => { resolveSatisfy = resolve; }));

    renderList([open]);
    fireEvent.click(screen.getByRole("checkbox"));

    expect((screen.getByRole("checkbox") as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(false);

    resolveSatisfy(reply({ id: "r-1", status: "SATISFIED" }));
    await waitFor(() => expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(true));
  });

  it("renders a write-scope 403 as an explanation, not a disappearance", async () => {
    fetchMock.mockResolvedValueOnce(
      reply({ detail: "Stage \"Legal Review\" write scope OWNER_ONLY does not admit the caller" }, 403),
    );

    renderList([open]);
    fireEvent.click(screen.getByRole("checkbox"));

    await waitFor(() => expect(screen.getByText(/write scope OWNER_ONLY does not admit/)).not.toBeNull());
    expect(screen.getByText("Collect ID")).not.toBeNull();
  });

  it("renders DOCUMENT requirements as the design's document chips, not a checkbox", () => {
    renderList([document]);
    expect(screen.queryByRole("checkbox")).toBeNull();
    expect(screen.getByText("Passport scan")).not.toBeNull();
  });

  it("hides waive without requirement.waive", () => {
    permissions = { "milestone.complete": ["ALL"] };
    renderList([open]);
    expect(screen.queryByRole("button", { name: /waive/i })).toBeNull();
  });

  it("offers waive to someone holding requirement.waive", () => {
    renderList([open]);
    expect(screen.getByRole("button", { name: /waive/i })).not.toBeNull();
  });
});
