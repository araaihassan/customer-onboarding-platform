import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { UpdateTaskRequest } from "@/lib/api/tasks";

const show = vi.fn();
vi.mock("@/components/ui/Toast", () => ({ useToast: () => ({ show }) }));

const { ReassignDialog } = await import("./ReassignDialog");
const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body ?? {}), json: async () => body } as unknown as Response;
}

const theCase = {
  id: "c-1",
  customerId: "cust-1",
  name: "Acme onboarding",
  ownerUserId: "u-1",
  owningDepartmentId: "d-1",
  owningTeamId: "t-1",
  attributes: { region: "EMEA", tier: "gold" },
};
const users = {
  content: [
    { id: "u-1", fullName: "Priya Shah", status: "ACTIVE", userType: "INTERNAL" },
    { id: "u-2", fullName: "Sam Lee", status: "ACTIVE", userType: "INTERNAL" },
    { id: "u-9", fullName: "Gone Person", status: "INACTIVE", userType: "INTERNAL" },
  ],
};
const task = {
  id: "tk-1",
  caseId: "c-1",
  milestoneId: "m-1",
  requirementId: "r-1",
  title: "Collect KYC",
  description: "All of it",
  priority: "HIGH",
  status: "IN_PROGRESS",
  assigneeId: "u-1",
  dueDate: "2026-10-09",
};
const tasks = [
  task,
  { ...task, id: "tk-2", title: "Done thing", status: "COMPLETED" },
  { ...task, id: "tk-3", title: "Cancelled thing", status: "CANCELLED" },
  { ...task, id: "tk-4", title: "Unowned thing", assigneeId: undefined },
];

function route(over: Record<string, () => Response> = {}) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const key = `${method} ${url}`;
    for (const [pattern, handler] of Object.entries(over)) if (key === pattern) return handler();
    if (key === "GET /api/t/acme/cases/c-1") return reply(theCase);
    if (key === "GET /api/t/acme/cases/c-1/tasks") return reply(tasks);
    if (key.startsWith("GET /api/t/acme/admin/users")) return reply(users);
    if (method === "PUT" && url.endsWith("/cases/c-1")) return reply(theCase);
    if (method === "PUT" && url.includes("/tasks/")) return reply(task);
    return reply({}, 404);
  });
}

function putBody(match: string) {
  const call = fetchMock.mock.calls.find(([u, i]) => (i as RequestInit | undefined)?.method === "PUT" && String(u).includes(match));
  return call ? JSON.parse((call[1] as RequestInit).body as string) : undefined;
}

function renderDialog(onClose = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const spy = vi.spyOn(client, "invalidateQueries");
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  render(<ReassignDialog caseId="c-1" onClose={onClose} />, { wrapper });
  return { onClose, spy };
}

beforeEach(() => {
  fetchMock.mockReset();
  show.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
  route();
});
afterEach(cleanup);

describe("ReassignDialog", () => {
  it("offers the case owner and only open, assigned tasks", async () => {
    renderDialog();
    const owner = (await screen.findByLabelText("Case owner")) as HTMLSelectElement;
    await waitFor(() => expect(owner.value).toBe("u-1"));
    expect(within(owner).queryByText("Gone Person")).toBeNull();
    expect(await screen.findByText("Collect KYC")).toBeInTheDocument();
    expect(screen.queryByText("Done thing")).toBeNull();
    expect(screen.queryByText("Cancelled thing")).toBeNull();
    expect(screen.queryByText("Unowned thing")).toBeNull();
    expect(screen.getAllByLabelText("Assignee")).toHaveLength(1);
  });

  it("saves the owner with a full-replace body that carries every other field untouched", async () => {
    const { onClose, spy } = renderDialog();
    const owner = await screen.findByLabelText("Case owner");
    await waitFor(() => expect((owner as HTMLSelectElement).value).toBe("u-1"));
    fireEvent.change(owner, { target: { value: "u-2" } });
    fireEvent.click(screen.getByRole("button", { name: "Reassign owner" }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(putBody("/cases/c-1")).toEqual({
      name: "Acme onboarding",
      ownerUserId: "u-2",
      owningDepartmentId: "d-1",
      owningTeamId: "t-1",
      attributes: { region: "EMEA", tier: "gold" },
    });
    expect(show).toHaveBeenCalledWith("Reassigned");
    expect(spy.mock.calls.map((c) => JSON.stringify((c[0] as { queryKey: unknown }).queryKey))).toContain(
      JSON.stringify(["sla", "exceptions"]),
    );
  });

  it("saves a task with its full current fields and only the assignee changed", async () => {
    const { onClose } = renderDialog();
    const select = await screen.findByLabelText("Assignee");
    await waitFor(() => expect((select as HTMLSelectElement).value).toBe("u-1"));
    fireEvent.change(select, { target: { value: "u-2" } });
    fireEvent.click(screen.getByRole("button", { name: "Reassign task Collect KYC" }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const body = putBody("/tasks/tk-1");
    expect(body).toEqual({
      title: "Collect KYC",
      description: "All of it",
      priority: "HIGH",
      assigneeId: "u-2",
      dueDate: "2026-10-09",
      milestoneId: "m-1",
    });
    // Every field the request type accepts is carried: tsc fails here if the type gains one.
    const keys: Record<keyof UpdateTaskRequest, true> = {
      title: true,
      description: true,
      priority: true,
      assigneeId: true,
      dueDate: true,
      milestoneId: true,
    };
    expect(Object.keys(body).sort()).toEqual(Object.keys(keys).sort());
    expect(show).toHaveBeenCalledWith("Reassigned");
  });

  it("keeps a current owner outside the fetched page selectable and selected", async () => {
    route({ "GET /api/t/acme/cases/c-1": () => reply({ ...theCase, ownerUserId: "u-far" }) });
    renderDialog();
    const owner = (await screen.findByLabelText("Case owner")) as HTMLSelectElement;
    await waitFor(() => expect(owner.value).toBe("u-far"));
  });

  it("shows the problem detail on a 404 and stays open", async () => {
    route({
      "PUT /api/t/acme/cases/c-1": () => reply({ title: "Not Found", status: 404, detail: "That user is not in your scope" }, 404),
    });
    const { onClose } = renderDialog();
    const owner = await screen.findByLabelText("Case owner");
    await waitFor(() => expect((owner as HTMLSelectElement).value).toBe("u-1"));
    fireEvent.change(owner, { target: { value: "u-2" } });
    fireEvent.click(screen.getByRole("button", { name: "Reassign owner" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That user is not in your scope");
    expect(onClose).not.toHaveBeenCalled();
  });

  it("disables saving until the selection changes", async () => {
    renderDialog();
    await screen.findByLabelText("Case owner");
    expect(screen.getByRole("button", { name: "Reassign owner" })).toBeDisabled();
  });

  it("shows an error state when the case cannot be loaded", async () => {
    route({ "GET /api/t/acme/cases/c-1": () => reply({}, 500) });
    renderDialog();
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
