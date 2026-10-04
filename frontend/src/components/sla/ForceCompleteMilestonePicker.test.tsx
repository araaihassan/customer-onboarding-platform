import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { ForceCompleteMilestonePicker } from "./ForceCompleteMilestonePicker";

const fetchMock = vi.fn();
function reply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body ?? {}), json: async () => body } as unknown as Response;
}
const roadmap = {
  stages: [
    { id: "s-1", name: "Intake", ordinal: 1, milestones: [{ id: "m-0", name: "Old", status: "ACTIVE" }] },
    {
      id: "s-2",
      name: "Verification",
      ordinal: 2,
      milestones: [
        { id: "m-1", name: "Collect KYC", status: "ACTIVE" },
        { id: "m-2", name: "Sanctions check", status: "PENDING" },
        { id: "m-3", name: "Finished", status: "DONE" },
        { id: "m-4", name: "Skipped one", status: "SKIPPED" },
      ],
    },
  ],
};

function serve(road: unknown, approvals: unknown, approvalsStatus = 200) {
  fetchMock.mockImplementation(async (url: string) =>
    String(url).endsWith("/approvals") ? reply(approvals, approvalsStatus) : reply(road),
  );
}

function renderPicker(stageName: string | undefined, onPick = vi.fn(), onClose = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  render(<ForceCompleteMilestonePicker caseId="c-1" stageName={stageName} onPick={onPick} onClose={onClose} />, { wrapper });
  return { onPick, onClose };
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
  serve(roadmap, []);
});
afterEach(cleanup);

describe("ForceCompleteMilestonePicker", () => {
  it("offers only the current stage's force-completable milestones and passes the chosen id on", async () => {
    const { onPick } = renderPicker("Verification");
    await screen.findByRole("button", { name: "Collect KYC" });
    expect(screen.getByRole("button", { name: "Sanctions check" })).toBeInTheDocument();
    expect(screen.queryByText("Finished")).toBeNull();
    expect(screen.queryByText("Skipped one")).toBeNull();
    expect(screen.queryByText("Old")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Sanctions check" }));
    expect(onPick).toHaveBeenCalledWith("m-2");
  });

  it("excludes a milestone that already has a pending force-complete request", async () => {
    serve(roadmap, [{ id: "a-1", kind: "FORCE_COMPLETE", milestoneId: "m-1", status: "PENDING" }]);
    renderPicker("Verification");
    await screen.findByRole("button", { name: "Sanctions check" });
    expect(screen.queryByRole("button", { name: "Collect KYC" })).toBeNull();
  });

  it("still offers a milestone whose earlier force-complete request was decided", async () => {
    serve(roadmap, [{ id: "a-1", kind: "FORCE_COMPLETE", milestoneId: "m-1", status: "REJECTED" }]);
    renderPicker("Verification");
    expect(await screen.findByRole("button", { name: "Collect KYC" })).toBeInTheDocument();
  });

  it("says so when nothing in the stage can be force-completed", async () => {
    serve(
      {
        stages: [
          {
            id: "s-2",
            name: "Verification",
            milestones: [
              { id: "m-3", name: "Finished", status: "DONE" },
              { id: "m-4", name: "Skipped one", status: "SKIPPED" },
              { id: "m-1", name: "Collect KYC", status: "ACTIVE" },
            ],
          },
        ],
      },
      [{ id: "a-1", kind: "FORCE_COMPLETE", milestoneId: "m-1", status: "PENDING" }],
    );
    renderPicker("Verification");
    expect(await screen.findByText("No milestone can be force-completed.")).toBeInTheDocument();
    expect(screen.queryAllByRole("listitem")).toHaveLength(0);
  });

  it("shows an error state when the roadmap cannot be loaded", async () => {
    fetchMock.mockImplementation(async () => reply({}, 500));
    renderPicker("Verification");
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });

  it("shows an error state when the approvals cannot be loaded", async () => {
    serve(roadmap, {}, 500);
    renderPicker("Verification");
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
