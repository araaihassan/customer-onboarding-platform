import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import { ProgrammeParticipants } from "../ProgrammeParticipants";

let permissions: Record<string, string[]> = {};

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

const fetchMock = vi.fn();

const users = [
  { id: "u-1", fullName: "Ada Okonjo", email: "ada@northwind.test" },
  { id: "u-2", fullName: "Ben Cole", email: "ben@northwind.test" },
];

let addStatus = 204;

function jsonReply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body ?? {}),
    json: async () => body,
  } as unknown as Response;
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<ProgrammeParticipants programmeId="prog-1" />, { wrapper: Wrapper });
}

beforeEach(() => {
  permissions = { "programme.manage": ["ALL"], "user.view": ["ALL"] };
  addStatus = 204;
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.includes("/admin/users")) return jsonReply({ content: users });
    if (url.includes("/participants") && init?.method === "POST") {
      return addStatus === 204 ? jsonReply(undefined, 204) : jsonReply({}, addStatus);
    }
    return jsonReply({});
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("ProgrammeParticipants", () => {
  it("renders nothing for someone without programme.manage", () => {
    permissions = {};
    const { container } = renderPanel();
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the panel and its explanation for someone holding programme.manage", () => {
    renderPanel();
    expect(screen.getByText("Participants")).toBeInTheDocument();
    expect(screen.getByText("No participants added this session")).toBeInTheDocument();
  });

  /**
   * Participation grants read of the programme only -- adding someone must not
   * silently escalate them onto the programme's journeys, so the checkbox
   * defaults to unchecked (design spec §6.3).
   */
  it("adds a participant with alsoGrantJourneyAccess defaulted to false", async () => {
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "Add participant" }));
    const dialog = await screen.findByRole("dialog", { name: "Add participant" });

    // The user list loads asynchronously; wait for the real option before
    // selecting it, or a select with no matching <option> yet silently ignores
    // the change.
    await within(dialog).findByRole("option", { name: /ada okonjo/i });
    fireEvent.change(within(dialog).getByLabelText("Person"), { target: { value: "u-1" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() =>
      expect(fetchMock.mock.calls.some((c) => (c[0] as string).includes("/participants") && (c[1] as RequestInit)?.method === "POST")).toBe(true),
    );
    const call = fetchMock.mock.calls.find(
      (c) => (c[0] as string).includes("/participants") && (c[1] as RequestInit)?.method === "POST",
    )!;
    const body = JSON.parse((call[1] as RequestInit).body as string);
    expect(body).toMatchObject({ userId: "u-1", relationshipType: "PARTICIPANT", alsoGrantJourneyAccess: false });
  });

  it("shows a successfully added participant in the session log, closing the dialog", async () => {
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "Add participant" }));
    const dialog = await screen.findByRole("dialog", { name: "Add participant" });
    await within(dialog).findByRole("option", { name: /ada okonjo/i });
    fireEvent.change(within(dialog).getByLabelText("Person"), { target: { value: "u-1" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(screen.getByText("Ada Okonjo")).toBeInTheDocument());
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByText("No participants added this session")).toBeNull();
  });

  it("reports a failed add instead of falling silent", async () => {
    addStatus = 500;
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "Add participant" }));
    const dialog = await screen.findByRole("dialog", { name: "Add participant" });
    await within(dialog).findByRole("option", { name: /ada okonjo/i });
    fireEvent.change(within(dialog).getByLabelText("Person"), { target: { value: "u-1" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(within(dialog).getByRole("alert").textContent).toBe("Something went wrong"));
    // Still open, and still offering the action, because it did not happen.
    expect(within(dialog).getByRole("button", { name: "Add" })).toBeInTheDocument();
  });
});
