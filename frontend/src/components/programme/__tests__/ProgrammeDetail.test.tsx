import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { ProgrammeDetail as ProgrammeDetailData } from "@/lib/api/programmes";
import { ProgrammeDetail } from "../ProgrammeDetail";

let permissions: Record<string, string[]> = {};

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const fetchMock = vi.fn();

function jsonReply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body ?? {}),
    json: async () => body,
  } as unknown as Response;
}

const detail: ProgrammeDetailData = {
  programme: {
    id: "prog-1",
    name: "Northwind rollout",
    customerId: "cust-1",
    customerName: "Northwind Foods",
    description: "The full multi-region rollout for Northwind.",
    status: "ACTIVE",
  },
  journeys: [
    { caseId: "case-1", name: "EMEA onboarding", status: "COMPLETED", progressPercent: 100 },
    { caseId: "case-200", name: "APAC onboarding", status: "ON_HOLD", progressPercent: 10 },
  ],
  rolledUpProgressPercent: 62,
  journeysCovered: 2,
};

function renderDetail(data: ProgrammeDetailData = detail) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<ProgrammeDetail detail={data} slug="acme" />, { wrapper: Wrapper });
}

beforeEach(() => {
  permissions = {};
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string) => {
    if (url.includes("/admin/users")) return jsonReply({ content: [] });
    return jsonReply({});
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("ProgrammeDetail", () => {
  it("renders the programme's name, status and customer", () => {
    renderDetail();
    expect(screen.getByText("Northwind rollout")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Northwind Foods" })).toHaveAttribute(
      "href",
      "/t/acme/customers/cust-1",
    );
  });

  it("renders the programme description", () => {
    renderDetail();
    expect(screen.getByText("The full multi-region rollout for Northwind.")).toBeInTheDocument();
  });

  it("passes the rolled-up rollup figures through to the rollup bar", () => {
    renderDetail();
    expect(screen.getByText("62%")).toBeInTheDocument();
    expect(screen.getByText("across 2 journeys")).toBeInTheDocument();
  });

  it("lists the programme's journeys with a count in the section header", () => {
    renderDetail();
    expect(screen.getByText("Journeys")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.getByText("EMEA onboarding")).toBeInTheDocument();
    expect(screen.getByText("APAC onboarding")).toBeInTheDocument();
    // Colour is never the only signal: ON_HOLD carries the word too.
    expect(screen.getByText("On hold")).toBeInTheDocument();
  });

  it("links each visible journey into the case workspace", () => {
    renderDetail();
    expect(screen.getByRole("link", { name: "EMEA onboarding" })).toHaveAttribute(
      "href",
      "/t/acme/customers/cust-1/cases/case-1",
    );
  });

  it("offers a link back to the programme picker", () => {
    renderDetail();
    expect(screen.getByRole("link", { name: /back to programmes/i })).toHaveAttribute(
      "href",
      "/t/acme/programmes",
    );
  });

  it("starts its own content at h2 -- the shell owns the h1", () => {
    renderDetail();
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(screen.getAllByRole("heading", { level: 2 }).length).toBeGreaterThan(0);
  });

  it("hides the participants panel from someone without programme.manage", () => {
    renderDetail();
    expect(screen.queryByText("Participants")).toBeNull();
  });

  it("shows the participants panel to someone holding programme.manage", () => {
    permissions = { "programme.manage": ["ALL"] };
    renderDetail();
    expect(screen.getByText("Participants")).toBeInTheDocument();
  });

  it("renders nothing for an absent programme rather than throwing", () => {
    const { container } = renderDetail({ programme: undefined, journeys: [], rolledUpProgressPercent: 0, journeysCovered: 0 });
    expect(container).toBeEmptyDOMElement();
  });
});
