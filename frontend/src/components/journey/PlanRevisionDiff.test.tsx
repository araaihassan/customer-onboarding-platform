import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "@testing-library/jest-dom/vitest";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { PlanRevisionDiff } from "./PlanRevisionDiff";

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

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("PlanRevisionDiff", () => {
  it("marks a moved date with both a colour and the word, alongside both dates", async () => {
    fetchMock.mockResolvedValue(
      reply({
        rows: [
          {
            milestoneDefinitionId: "md-1",
            milestoneName: "Kickoff",
            previousDueDate: "2026-09-01",
            currentDueDate: "2026-09-08",
            changeKind: "DATE_CHANGED",
          },
        ],
      }),
    );

    render(<PlanRevisionDiff caseId="c1" revisionId="r-2" againstId="r-1" />, { wrapper: makeWrapper() });

    await waitFor(() => expect(screen.getByText("Kickoff")).toBeInTheDocument());
    expect(screen.getByText("Moved")).toBeInTheDocument();
    expect(screen.getByText(/2026-09-01/)).toBeInTheDocument();
    expect(screen.getByText(/2026-09-08/)).toBeInTheDocument();
  });

  it("drops unchanged rows entirely", async () => {
    fetchMock.mockResolvedValue(
      reply({
        rows: [
          { milestoneDefinitionId: "md-1", milestoneName: "Kickoff", changeKind: "UNCHANGED" },
        ],
      }),
    );

    const { container } = render(<PlanRevisionDiff caseId="c1" revisionId="r-2" againstId="r-1" />, {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("renders nothing when there is no prior revision to diff against", () => {
    const { container } = render(<PlanRevisionDiff caseId="c1" revisionId="r-1" againstId="" />, {
      wrapper: makeWrapper(),
    });
    expect(container).toBeEmptyDOMElement();
  });
});
