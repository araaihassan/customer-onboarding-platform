import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug, __setAccessToken } from "@/lib/api/client";
import { StageInspector } from "./StageInspector";
import type { StageDraft } from "./draftState";

afterEach(cleanup);

const fetchMock = vi.fn();

function makeWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue({
    ok: true,
    status: 200,
    text: async () => "[]",
    json: async () => [],
  } as unknown as Response);
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

const threeStages: StageDraft[] = [
  { key: "reg", name: "Registration", milestones: [], branchRules: [] },
  { key: "legal", name: "Legal Review", milestones: [], branchRules: [] },
  { key: "live", name: "Go Live", milestones: [], branchRules: [] },
];

function renderInspector(stage: StageDraft, index: number, onChange = vi.fn(), readOnly = false) {
  render(
    <StageInspector
      stage={stage}
      stageIndex={index}
      stages={threeStages}
      attributes={[]}
      onChange={onChange}
      readOnly={readOnly}
    />,
    { wrapper: makeWrapper() },
  );
  return onChange;
}

describe("StageInspector", () => {
  it("renders the notification template field disabled with an explanation", () => {
    renderInspector(threeStages[1]!, 1);

    const field = screen.getByLabelText(/notification template/i);
    expect((field as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText(/arrives with notifications/i)).not.toBeNull();
  });

  it("calls onChange when the stage name is edited", () => {
    const onChange = renderInspector(threeStages[1]!, 1);

    fireEvent.change(screen.getByLabelText("Stage name"), { target: { value: "Compliance Review" } });

    expect(onChange).toHaveBeenCalledWith({ name: "Compliance Review" });
  });

  it("toggles requiresApproval through the switch", () => {
    const onChange = renderInspector(threeStages[1]!, 1);

    fireEvent.click(screen.getByRole("switch", { name: "Requires approval to exit" }));

    expect(onChange).toHaveBeenCalledWith({ requiresApproval: true });
  });

  it("does not offer a branch rule affordance on the last stage", () => {
    renderInspector(threeStages[2]!, 2);
    expect(screen.queryByText("Add branch rule")).toBeNull();
  });

  it("offers a branch rule affordance on a stage with stages after it", () => {
    renderInspector(threeStages[0]!, 0);
    expect(screen.getByText("Add branch rule")).not.toBeNull();
  });

  /**
   * ConditionEditor's operator <select> shows "equals" as its unselected
   * default -- a value the DOM displays, not one written into state. Adding a
   * rule must seed a real operator, or an admin who never touches that
   * dropdown submits a NULL the database's NOT NULL constraint rejects.
   */
  it("seeds a real source and operator when a branch rule is added, not just a display default", () => {
    const onChange = renderInspector(threeStages[0]!, 0);

    fireEvent.click(screen.getByText("Add branch rule"));

    expect(onChange).toHaveBeenCalledWith({
      branchRules: [{ condition: { source: "ATTRIBUTE", operator: "EQ" } }],
    });
  });

  /**
   * A published version is frozen -- browsing a stage's configuration must
   * still work, but nothing here has a Save button any more, so a field that
   * silently accepts edits would mislead an admin into thinking a change was
   * made. Every control is wrapped in a native fieldset[disabled], which
   * cascades to all of them (input, select, button, however deeply nested)
   * per the HTML spec -- real browsers implement this; jsdom does not, so
   * this asserts the fieldset carries the attribute rather than the cascaded
   * effect on a descendant, and the cascade itself is verified live.
   */
  it("wraps the stage's fields in a disabled fieldset when readOnly, without hiding its configuration", () => {
    renderInspector(threeStages[1]!, 1, vi.fn(), true);

    const fieldset = screen.getByLabelText("Stage name").closest("fieldset");
    expect(fieldset?.disabled).toBe(true);
    expect(screen.getByText("Legal Review")).not.toBeNull();
  });
});
