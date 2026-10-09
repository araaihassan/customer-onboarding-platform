import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useCreateNotificationTemplate, useUpdateNotificationTemplate } from "@/lib/api/notifications";
import type { NotificationTemplate } from "@/lib/api/notifications";
import { ApiError } from "@/lib/api/client";

vi.mock("@/lib/api/notifications", () => ({
  useCreateNotificationTemplate: vi.fn(),
  useUpdateNotificationTemplate: vi.fn(),
}));
const show = vi.fn();
vi.mock("@/components/ui/Toast", () => ({ useToast: () => ({ show }) }));

const { TemplateDialog } = await import("./TemplateDialog");

afterEach(cleanup);
beforeEach(() => vi.clearAllMocks());

type Opts = { onSuccess?: () => void; onError?: (e: Error) => void };

function prime(fail?: Error) {
  const make = () =>
    vi.fn((_v: unknown, opts?: Opts) => {
      if (fail) opts?.onError?.(fail);
      else opts?.onSuccess?.();
    });
  const create = make();
  const update = make();
  vi.mocked(useCreateNotificationTemplate).mockReturnValue({ mutate: create, isPending: false } as never);
  vi.mocked(useUpdateNotificationTemplate).mockReturnValue({ mutate: update, isPending: false } as never);
  return { create, update };
}

function fillBasics() {
  fireEvent.change(screen.getByLabelText("Key"), { target: { value: "kickoff" } });
  fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Kickoff" } });
  fireEvent.change(screen.getByLabelText("Subject when a stage is entered"), { target: { value: "Hi {customer}" } });
  fireEvent.change(screen.getByLabelText("Body when a stage is entered"), { target: { value: "Now in {stage}" } });
}

const existing: NotificationTemplate = {
  id: "t9",
  key: "welcome",
  name: "Welcome",
  enteredSubject: "S",
  enteredBody: "B",
  exitedSubject: "XS",
  exitedBody: "XB",
  active: true,
};

describe("TemplateDialog", () => {
  it("create mode has an editable key", () => {
    prime();
    render(<TemplateDialog onClose={vi.fn()} />);
    expect((screen.getByLabelText("Key") as HTMLInputElement).readOnly).toBe(false);
    expect(screen.getByRole("dialog", { name: "New template" })).toBeTruthy();
  });

  it("edit mode shows the key read-only and prefilled", () => {
    prime();
    render(<TemplateDialog template={existing} onClose={vi.fn()} />);
    const key = screen.getByLabelText("Key") as HTMLInputElement;
    expect(key.value).toBe("welcome");
    expect(key.readOnly).toBe(true);
  });

  it("lists the real placeholder set in mono", () => {
    prime();
    render(<TemplateDialog onClose={vi.fn()} />);
    for (const p of ["{case}", "{customer}", "{stage}", "{owner}"]) {
      const el = screen.getByText(p);
      expect(el.getAttribute("style")).toContain("font-family-data");
    }
  });

  it("hides the exit fields when off and sends null for both (positive control: on sends them)", () => {
    const { create } = prime();
    render(<TemplateDialog onClose={vi.fn()} />);
    fillBasics();
    expect(screen.queryByLabelText("Subject when a stage is exited")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(create.mock.calls[0]![0]).toEqual({
      key: "kickoff",
      name: "Kickoff",
      enteredSubject: "Hi {customer}",
      enteredBody: "Now in {stage}",
      exitedSubject: null,
      exitedBody: null,
      active: true,
    });
    cleanup();
    const again = prime();
    render(<TemplateDialog onClose={vi.fn()} />);
    fillBasics();
    fireEvent.click(screen.getByRole("switch", { name: "Also alert on exit" }));
    fireEvent.change(screen.getByLabelText("Subject when a stage is exited"), { target: { value: "Bye" } });
    fireEvent.change(screen.getByLabelText("Body when a stage is exited"), { target: { value: "Left" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(again.create.mock.calls[0]![0]).toMatchObject({ exitedSubject: "Bye", exitedBody: "Left" });
  });

  it("turning exit off after typing still sends null", () => {
    const { update } = prime();
    render(<TemplateDialog template={existing} onClose={vi.fn()} />);
    expect(screen.getByLabelText("Subject when a stage is exited")).toBeTruthy();
    fireEvent.click(screen.getByRole("switch", { name: "Also alert on exit" }));
    expect(screen.queryByLabelText("Subject when a stage is exited")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(update.mock.calls[0]![0]).toEqual({
      id: "t9",
      body: {
        key: "welcome",
        name: "Welcome",
        enteredSubject: "S",
        enteredBody: "B",
        exitedSubject: null,
        exitedBody: null,
        active: true,
      },
    });
  });

  it("blank exit fields with the switch on send null, not empty strings", () => {
    const { create } = prime();
    render(<TemplateDialog onClose={vi.fn()} />);
    fillBasics();
    fireEvent.click(screen.getByRole("switch", { name: "Also alert on exit" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(create.mock.calls[0]![0]).toMatchObject({ exitedSubject: null, exitedBody: null });
  });

  it("a half-filled exit pair is refused before sending", () => {
    const { create } = prime();
    render(<TemplateDialog onClose={vi.fn()} />);
    fillBasics();
    fireEvent.click(screen.getByRole("switch", { name: "Also alert on exit" }));
    fireEvent.change(screen.getByLabelText("Subject when a stage is exited"), { target: { value: "Bye" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(create).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toContain("both");
  });

  it("success toasts and closes", () => {
    prime();
    const onClose = vi.fn();
    render(<TemplateDialog onClose={onClose} />);
    fillBasics();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(show).toHaveBeenCalledWith("Template saved");
    expect(onClose).toHaveBeenCalled();
  });

  it("a 409 shows the duplicate-key message and keeps the dialog open", () => {
    prime(new ApiError(409, "conflict"));
    const onClose = vi.fn();
    render(<TemplateDialog onClose={onClose} />);
    fillBasics();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.getByText("A template with this key already exists")).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("a 422 shows the server detail", () => {
    prime(new ApiError(422, JSON.stringify({ detail: "Unknown placeholder {foo} in the entered body" })));
    render(<TemplateDialog onClose={vi.fn()} />);
    fillBasics();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.getByRole("alert").textContent).toContain("Unknown placeholder {foo} in the entered body");
    expect(screen.queryByText("A template with this key already exists")).toBeNull();
  });

  it("mirrors the DTO max lengths", () => {
    prime();
    render(<TemplateDialog onClose={vi.fn()} />);
    expect(screen.getByLabelText("Name").getAttribute("maxlength")).toBe("120");
    expect(screen.getByLabelText("Subject when a stage is entered").getAttribute("maxlength")).toBe("200");
    expect(screen.getByLabelText("Body when a stage is entered").getAttribute("maxlength")).toBe("2000");
  });

  it("Save is disabled until the required fields are filled", () => {
    prime();
    render(<TemplateDialog onClose={vi.fn()} />);
    expect((screen.getByRole("button", { name: "Save" }) as HTMLButtonElement).disabled).toBe(true);
    fillBasics();
    expect((screen.getByRole("button", { name: "Save" }) as HTMLButtonElement).disabled).toBe(false);
  });
});
