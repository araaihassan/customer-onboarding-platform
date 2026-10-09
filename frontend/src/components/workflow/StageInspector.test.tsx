import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
  describe("Notification template", () => {
    function answerOptions(options: unknown) {
      fetchMock.mockImplementation(async (url: string) => {
        const body = String(url).includes("/notification-templates/options") ? options : [];
        return {
          ok: true,
          status: 200,
          text: async () => JSON.stringify(body),
          json: async () => body,
        } as unknown as Response;
      });
    }
    const field = () => screen.getByLabelText("Notification template") as HTMLSelectElement;
    const optionLabels = () => Array.from(field().options).map((o) => o.textContent);

    it("offersNoneAndTheActiveTemplates", async () => {
      answerOptions([{ key: "kickoff", name: "Kickoff" }]);
      const onChange = renderInspector(threeStages[1]!, 1);

      await waitFor(() => expect(optionLabels()).toEqual(["None", "Kickoff"]));
      fireEvent.change(field(), { target: { value: "kickoff" } });
      expect(onChange).toHaveBeenCalledWith({ notificationTemplateKey: "kickoff" });
      cleanup();

      const again = renderInspector({ ...threeStages[1]!, notificationTemplateKey: "kickoff" }, 1);
      await waitFor(() => expect(optionLabels()).toEqual(["None", "Kickoff"]));
      expect(field().value).toBe("kickoff");
      fireEvent.change(field(), { target: { value: "" } });
      expect(again).toHaveBeenCalledWith({ notificationTemplateKey: undefined });
    });

    it("keepsAKeyThatIsNoLongerOffered", async () => {
      answerOptions([{ key: "kickoff", name: "Kickoff" }]);
      renderInspector({ ...threeStages[1]!, notificationTemplateKey: "retired" }, 1);

      await waitFor(() => expect(optionLabels()).toContain("Kickoff"));
      expect(field().value).toBe("retired");
      expect(field().selectedOptions[0]?.textContent).toBe("retired (inactive)");
    });

    it("keeps the current key selected while options load or when the request fails", async () => {
      fetchMock.mockResolvedValue({
        ok: false,
        status: 500,
        text: async () => "",
        json: async () => ({}),
      } as unknown as Response);
      const onChange = renderInspector({ ...threeStages[1]!, notificationTemplateKey: "kickoff" }, 1);

      expect(field().value).toBe("kickoff");
      await waitFor(() => expect(fetchMock).toHaveBeenCalled());
      expect(field().value).toBe("kickoff");
      expect(field().selectedOptions[0]?.textContent).toBe("kickoff (inactive)");
      expect(onChange).not.toHaveBeenCalled();
    });

    it("shows no inactive entry for an unset key", async () => {
      answerOptions([{ key: "kickoff", name: "Kickoff" }]);
      renderInspector(threeStages[1]!, 1);
      await waitFor(() => expect(optionLabels()).toEqual(["None", "Kickoff"]));
      expect(field().value).toBe("");
    });

    it("isDisabledReadOnly", () => {
      renderInspector({ ...threeStages[1]!, notificationTemplateKey: "kickoff" }, 1, vi.fn(), true);
      expect(field().closest("fieldset")?.disabled).toBe(true);
      expect(field().tagName).toBe("SELECT");
    });

    it("no longer carries the 'arrives with notifications' hint", () => {
      renderInspector(threeStages[1]!, 1);
      expect(screen.queryByText(/arrives with notifications/i)).toBeNull();
      // positive control: the field itself still renders
      expect(field()).not.toBeNull();
    });
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

  describe("Pause on customer", () => {
    it("reads as on for a draft created before the field existed (undefined)", () => {
      renderInspector(threeStages[1]!, 1);
      expect(screen.getByRole("switch", { name: "Pause on customer" }).getAttribute("aria-checked")).toBe("true");
    });

    it("reads as off when the stage says false, and on when it says true", () => {
      renderInspector({ ...threeStages[1]!, pausesOnCustomer: false }, 1);
      expect(screen.getByRole("switch", { name: "Pause on customer" }).getAttribute("aria-checked")).toBe("false");
      cleanup();
      renderInspector({ ...threeStages[1]!, pausesOnCustomer: true }, 1);
      expect(screen.getByRole("switch", { name: "Pause on customer" }).getAttribute("aria-checked")).toBe("true");
    });

    it("emits an explicit false when toggled off, and true when toggled back on", () => {
      const onChange = renderInspector(threeStages[1]!, 1);
      fireEvent.click(screen.getByRole("switch", { name: "Pause on customer" }));
      expect(onChange).toHaveBeenCalledWith({ pausesOnCustomer: false });
      cleanup();
      const again = renderInspector({ ...threeStages[1]!, pausesOnCustomer: false }, 1);
      fireEvent.click(screen.getByRole("switch", { name: "Pause on customer" }));
      expect(again).toHaveBeenCalledWith({ pausesOnCustomer: true });
    });

    it("sits inside the disabled fieldset when readOnly", () => {
      renderInspector(threeStages[1]!, 1, vi.fn(), true);
      expect(screen.getByRole("switch", { name: "Pause on customer" }).closest("fieldset")?.disabled).toBe(true);
    });

    it("explains the effect when the stage has an SLA", () => {
      renderInspector({ ...threeStages[1]!, slaDays: 5 }, 1);
      expect(screen.getByText("Open customer document requests pause this stage's SLA clock.")).not.toBeNull();
      expect(screen.queryByText("Takes effect once the stage has an SLA.")).toBeNull();
    });

    it("says it takes effect once an SLA exists when slaDays is empty, but still shows the toggle", () => {
      renderInspector(threeStages[1]!, 1);
      expect(screen.getByText("Takes effect once the stage has an SLA.")).not.toBeNull();
      expect(screen.getByRole("switch", { name: "Pause on customer" })).not.toBeNull();
    });
  });
});
