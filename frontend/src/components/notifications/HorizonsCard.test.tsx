import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useNotificationPolicy, useUpdateNotificationPolicy } from "@/lib/api/notifications";
import { ApiError } from "@/lib/api/client";

vi.mock("@/lib/api/notifications", () => ({
  useNotificationPolicy: vi.fn(),
  useUpdateNotificationPolicy: vi.fn(),
}));

const show = vi.fn();
vi.mock("@/components/ui/Toast", () => ({ useToast: () => ({ show }) }));

const { HorizonsCard } = await import("./HorizonsCard");

afterEach(cleanup);
beforeEach(() => vi.clearAllMocks());

const policy = {
  autoRemind: { enabled: true, intervalDays: 3, max: 4 },
  horizons: {
    TASK_DUE: [1, 3],
    MILESTONE_DUE: [2],
    DOCUMENT_REQUEST_DUE: [],
    DOCUMENT_EXPIRY: [14, 30],
    AGREEMENT_EXPIRY: [30],
    AGREEMENT_RENEWAL: [60],
  },
};

function prime(o: { loading?: boolean; error?: boolean; fail?: Error; data?: unknown } = {}) {
  const mutate = vi.fn((_b: unknown, opts?: { onSuccess?: () => void; onError?: (e: Error) => void }) => {
    if (o.fail) opts?.onError?.(o.fail);
    else opts?.onSuccess?.();
  });
  vi.mocked(useNotificationPolicy).mockReturnValue({
    data: o.loading || o.error ? undefined : (o.data ?? policy),
    isLoading: Boolean(o.loading),
    isError: Boolean(o.error),
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useNotificationPolicy>);
  vi.mocked(useUpdateNotificationPolicy).mockReturnValue({ mutate, isPending: false } as never);
  return mutate;
}

function row(name: string) {
  return screen.getByRole("group", { name }) as HTMLElement;
}

function add(r: HTMLElement, value: string) {
  fireEvent.change(within(r).getByLabelText("Lead time in days"), { target: { value } });
  fireEvent.click(within(r).getByRole("button", { name: "Add lead time" }));
}

describe("HorizonsCard", () => {
  it("renders one row per kind with its unit caption", () => {
    prime();
    render(<HorizonsCard />);
    for (const k of ["Task due", "Milestone due", "Document request due"]) {
      expect(within(row(k)).getByText("business days before due")).toBeTruthy();
    }
    for (const k of ["Document expiry", "Agreement expiry", "Agreement renewal decision"]) {
      expect(within(row(k)).getByText("days before")).toBeTruthy();
    }
  });

  it("shows each lead as a chip with a labelled remove button", () => {
    prime();
    render(<HorizonsCard />);
    const expiry = row("Document expiry");
    expect(within(expiry).getByRole("button", { name: "Remove 14 days" }).textContent).toContain("14");
    fireEvent.click(within(expiry).getByRole("button", { name: "Remove 14 days" }));
    expect(within(expiry).queryByRole("button", { name: "Remove 14 days" })).toBeNull();
    expect(within(expiry).getByRole("button", { name: "Remove 30 days" })).toBeTruthy();
  });

  it("adds a valid lead time (positive control), kept sorted", () => {
    prime();
    render(<HorizonsCard />);
    const r = row("Task due");
    add(r, "2");
    const chips = within(r).getAllByRole("button", { name: /^Remove/ }).map((b) => b.getAttribute("aria-label"));
    expect(chips).toEqual(["Remove 1 days", "Remove 2 days", "Remove 3 days"]);
    expect(within(r).queryByRole("alert")).toBeNull();
  });

  it.each([["0"], ["91"], ["1.5"]])("refuses %s with an inline message", (v) => {
    const mutate = prime();
    render(<HorizonsCard />);
    const r = row("Task due");
    add(r, v);
    expect(within(r).getByRole("alert").textContent).toBe("Lead times are 1 to 90 days, at most five");
    expect(within(r).getAllByRole("button", { name: /^Remove/ })).toHaveLength(2);
    expect(mutate).not.toHaveBeenCalled();
  });

  it("refuses a duplicate", () => {
    prime();
    render(<HorizonsCard />);
    const r = row("Task due");
    add(r, "3");
    expect(within(r).getByRole("alert").textContent).toBe("That lead time is already set");
    expect(within(r).getAllByRole("button", { name: /^Remove/ })).toHaveLength(2);
  });

  it("refuses a sixth chip", () => {
    prime();
    render(<HorizonsCard />);
    const r = row("Task due");
    for (const v of ["5", "7", "10"]) add(r, v);
    expect(within(r).getAllByRole("button", { name: /^Remove/ })).toHaveLength(5);
    expect(within(r).queryByRole("alert")).toBeNull();
    add(r, "20");
    expect(within(r).getAllByRole("button", { name: /^Remove/ })).toHaveLength(5);
    expect(within(r).getByRole("alert")).toBeTruthy();
  });

  it("shows the auto-remind switch, interval and max", () => {
    prime();
    render(<HorizonsCard />);
    expect(screen.getByRole("switch", { name: "Automatic customer reminders" }).getAttribute("aria-checked")).toBe(
      "true",
    );
    expect((screen.getByLabelText("Every (business days)") as HTMLInputElement).value).toBe("3");
    expect((screen.getByLabelText("At most (reminders)") as HTMLInputElement).value).toBe("4");
  });

  it("disables Save for an out-of-range interval or max, and re-enables it when valid", () => {
    prime();
    render(<HorizonsCard />);
    const save = () => screen.getByRole("button", { name: "Save" }) as HTMLButtonElement;
    expect(save().disabled).toBe(false);
    fireEvent.change(screen.getByLabelText("Every (business days)"), { target: { value: "31" } });
    expect(save().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Every (business days)"), { target: { value: "30" } });
    fireEvent.change(screen.getByLabelText("At most (reminders)"), { target: { value: "11" } });
    expect(save().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("At most (reminders)"), { target: { value: "10" } });
    expect(save().disabled).toBe(false);
  });

  it("saves the whole policy, all six kinds, and toasts", () => {
    const mutate = prime();
    render(<HorizonsCard />);
    fireEvent.click(within(row("Task due")).getByRole("button", { name: "Remove 1 days" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(mutate).toHaveBeenCalledTimes(1);
    expect(mutate.mock.calls[0]![0]).toEqual({
      autoRemind: { enabled: true, intervalDays: 3, max: 4 },
      horizons: { ...policy.horizons, TASK_DUE: [3] },
    });
    expect(show).toHaveBeenCalledWith("Notification policy saved");
  });

  it("shows the server's detail inline on a 422", () => {
    prime({ fail: new ApiError(422, JSON.stringify({ detail: "A lead time is 1 to 90 days" })) });
    render(<HorizonsCard />);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.getByText("A lead time is 1 to 90 days")).toBeTruthy();
    expect(show).not.toHaveBeenCalled();
  });

  const refetched = { ...policy, horizons: { ...policy.horizons, MILESTONE_DUE: [7] } };

  it("keeps an unsaved edit when the policy is refetched (e.g. on window focus)", () => {
    prime();
    const { rerender } = render(<HorizonsCard />);
    add(row("Task due"), "5");
    expect(within(row("Task due")).getByRole("button", { name: "Remove 5 days" })).toBeTruthy();

    prime({ data: refetched });               // a new data object from a background refetch
    rerender(<HorizonsCard />);
    expect(within(row("Task due")).getByRole("button", { name: "Remove 5 days" })).toBeTruthy();
  });

  it("reseeds a pristine form from a refetch, and again after a successful save (positive control)", () => {
    prime();
    const { rerender } = render(<HorizonsCard />);
    prime({ data: refetched });
    rerender(<HorizonsCard />);
    expect(within(row("Milestone due")).getByRole("button", { name: "Remove 7 days" })).toBeTruthy();

    add(row("Task due"), "5");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    prime({ data: { ...refetched, horizons: { ...refetched.horizons, TASK_DUE: [1, 3, 5], MILESTONE_DUE: [8] } } });
    rerender(<HorizonsCard />);
    expect(within(row("Milestone due")).getByRole("button", { name: "Remove 8 days" })).toBeTruthy();
  });

  it("shows a skeleton while loading and an error state on failure", () => {
    prime({ loading: true });
    const { unmount } = render(<HorizonsCard />);
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    unmount();
    prime({ error: true });
    render(<HorizonsCard />);
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(screen.getByRole("button", { name: "Try again" })).toBeTruthy();
  });
});
