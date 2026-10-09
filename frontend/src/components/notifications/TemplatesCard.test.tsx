import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useNotificationTemplates, useUpdateNotificationTemplate } from "@/lib/api/notifications";
import type { NotificationTemplate } from "@/lib/api/notifications";

vi.mock("@/lib/api/notifications", () => ({
  useNotificationTemplates: vi.fn(),
  useUpdateNotificationTemplate: vi.fn(),
}));
vi.mock("./TemplateDialog", () => ({
  TemplateDialog: ({ template, onClose }: { template?: NotificationTemplate; onClose: () => void }) => (
    <div role="dialog" aria-label="stub">
      {template ? "edit: " + template.key : "create"}
      <button onClick={onClose}>close-stub</button>
    </div>
  ),
}));
const show = vi.fn();
vi.mock("@/components/ui/Toast", () => ({ useToast: () => ({ show }) }));

const { TemplatesCard } = await import("./TemplatesCard");

afterEach(cleanup);
beforeEach(() => vi.clearAllMocks());

const entryOnly: NotificationTemplate = {
  id: "t1",
  key: "welcome",
  name: "Welcome",
  enteredSubject: "Hi {customer}",
  enteredBody: "Stage {stage}",
  active: true,
};
const both: NotificationTemplate = {
  id: "t2",
  key: "handover",
  name: "Handover",
  enteredSubject: "In {stage}",
  enteredBody: "Body in",
  exitedSubject: "Out of {stage}",
  exitedBody: "Body out",
  active: false,
};

function prime(o: { loading?: boolean; error?: boolean; data?: NotificationTemplate[]; fail?: boolean } = {}) {
  const mutate = vi.fn((_v: unknown, opts?: { onSuccess?: () => void; onError?: (e: Error) => void }) => {
    if (o.fail) opts?.onError?.(new Error("x"));
    else opts?.onSuccess?.();
  });
  vi.mocked(useNotificationTemplates).mockReturnValue({
    data: o.loading || o.error ? undefined : (o.data ?? [entryOnly, both]),
    isLoading: Boolean(o.loading),
    isError: Boolean(o.error),
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useNotificationTemplates>);
  vi.mocked(useUpdateNotificationTemplate).mockReturnValue({ mutate, isPending: false } as never);
  return mutate;
}

function table() {
  return screen.getByRole("table");
}

describe("TemplatesCard", () => {
  it("lists key in mono, name, entry kind and the active word", () => {
    prime();
    render(<TemplatesCard />);
    const rows = within(table()).getAllByRole("row");
    const welcome = rows.find((r) => within(r).queryByText("welcome"))!;
    expect(within(welcome).getByText("Welcome")).toBeTruthy();
    expect(within(welcome).getByText("Entry")).toBeTruthy();
    expect(within(welcome).getByText("Active")).toBeTruthy();
    expect(within(welcome).getByText("welcome").getAttribute("style")).toContain("font-family-data");
    const handover = rows.find((r) => within(r).queryByText("handover"))!;
    expect(within(handover).getByText("Entry and exit")).toBeTruthy();
    expect(within(handover).getByText("Inactive")).toBeTruthy();
  });

  it("deactivating sends the full PUT with only active flipped (positive control)", () => {
    const mutate = prime();
    render(<TemplatesCard />);
    fireEvent.click(within(table()).getByRole("button", { name: "Deactivate Welcome" }));
    expect(mutate).toHaveBeenCalledTimes(1);
    expect(mutate.mock.calls[0]![0]).toEqual({
      id: "t1",
      body: {
        key: "welcome",
        name: "Welcome",
        enteredSubject: "Hi {customer}",
        enteredBody: "Stage {stage}",
        exitedSubject: null,
        exitedBody: null,
        active: false,
      },
    });
  });

  it("activating preserves the exited fields", () => {
    const mutate = prime();
    render(<TemplatesCard />);
    fireEvent.click(within(table()).getByRole("button", { name: "Activate Handover" }));
    expect(mutate.mock.calls[0]![0]).toEqual({
      id: "t2",
      body: {
        key: "handover",
        name: "Handover",
        enteredSubject: "In {stage}",
        enteredBody: "Body in",
        exitedSubject: "Out of {stage}",
        exitedBody: "Body out",
        active: true,
      },
    });
  });

  it("toasts on success and shows an error on failure", () => {
    prime();
    render(<TemplatesCard />);
    fireEvent.click(within(table()).getByRole("button", { name: "Deactivate Welcome" }));
    expect(show).toHaveBeenCalledWith("Template saved");
    cleanup();
    show.mockClear();
    prime({ fail: true });
    render(<TemplatesCard />);
    fireEvent.click(within(table()).getByRole("button", { name: "Deactivate Welcome" }));
    expect(show).not.toHaveBeenCalledWith("Template saved");
    expect(screen.getByRole("alert").textContent).toContain("Something went wrong");
  });

  it("opens the dialog to create and to edit", () => {
    prime();
    render(<TemplatesCard />);
    fireEvent.click(screen.getByRole("button", { name: "New template" }));
    expect(screen.getByRole("dialog").textContent).toContain("create");
    fireEvent.click(screen.getByText("close-stub"));
    expect(screen.queryByRole("dialog")).toBeNull();
    fireEvent.click(within(table()).getByRole("button", { name: "Edit Welcome" }));
    expect(screen.getByRole("dialog").textContent).toContain("edit: welcome");
  });

  it("shows the empty state with the New template button still present", () => {
    prime({ data: [] });
    render(<TemplatesCard />);
    expect(screen.getByText("No templates yet")).toBeTruthy();
    expect(screen.getByRole("button", { name: "New template" })).toBeTruthy();
  });

  it("shows a loading skeleton and an error with retry", () => {
    prime({ loading: true });
    render(<TemplatesCard />);
    expect(screen.getByLabelText("Loading")).toBeTruthy();
    cleanup();
    prime({ error: true });
    render(<TemplatesCard />);
    expect(screen.getByRole("alert").textContent).toContain("Something went wrong");
    expect(screen.getByRole("button", { name: "Try again" })).toBeTruthy();
  });
});
