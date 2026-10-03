import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import {
  useAddHoliday,
  useBusinessCalendar,
  useRemoveHoliday,
  useSlaPolicy,
  useUpdateBusinessCalendar,
  useUpdateSlaPolicy,
} from "@/lib/api/calendar";
import { ApiError } from "@/lib/api/client";

vi.mock("@/lib/api/calendar", () => ({
  useBusinessCalendar: vi.fn(),
  useSlaPolicy: vi.fn(),
  useUpdateBusinessCalendar: vi.fn(),
  useAddHoliday: vi.fn(),
  useRemoveHoliday: vi.fn(),
  useUpdateSlaPolicy: vi.fn(),
}));

const show = vi.fn();
vi.mock("@/components/ui/Toast", () => ({ useToast: () => ({ show }) }));

const { default: CalendarPage } = await import("./page");

afterEach(cleanup);
beforeEach(() => vi.clearAllMocks());

const calendar = {
  name: "Head office",
  timezone: "Asia/Riyadh",
  workingDays: [1, 2, 3, 4, 5],
  holidays: [
    { id: "h1", date: "2026-01-01", name: "New Year" },
    { id: "h2", date: "2026-12-25", name: "Year end" },
  ],
};
const policy = { atRiskDays: 0.3, escalateAfterOverdueDays: 3 };

type Opts = { onSuccess?: () => void; onError?: (e: Error) => void };

function prime(
  o: {
    cal?: unknown;
    pol?: unknown;
    loading?: boolean;
    error?: boolean;
    fail?: Record<string, Error>;
  } = {},
) {
  const mutates: Record<string, ReturnType<typeof vi.fn>> = {};
  const mk = (name: string) => {
    mutates[name] = vi.fn((_body: unknown, opts?: Opts) => {
      if (o.fail?.[name]) opts?.onError?.(o.fail[name]);
      else opts?.onSuccess?.();
    });
    return { mutate: mutates[name], isPending: false, reset: vi.fn() };
  };
  vi.mocked(useBusinessCalendar).mockReturnValue({
    data: o.loading || o.error ? undefined : (o.cal ?? calendar),
    isLoading: Boolean(o.loading),
    isError: Boolean(o.error),
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useBusinessCalendar>);
  vi.mocked(useSlaPolicy).mockReturnValue({
    data: o.loading || o.error ? undefined : (o.pol ?? policy),
    isLoading: Boolean(o.loading),
    isError: Boolean(o.error),
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useSlaPolicy>);
  vi.mocked(useUpdateBusinessCalendar).mockReturnValue(mk("calendar") as never);
  vi.mocked(useAddHoliday).mockReturnValue(mk("add") as never);
  vi.mocked(useRemoveHoliday).mockReturnValue(mk("remove") as never);
  vi.mocked(useUpdateSlaPolicy).mockReturnValue(mk("policy") as never);
  return mutates;
}

describe("business calendar page", () => {
  it("shows the three cards with the specified controls", () => {
    prime();
    render(<CalendarPage />);
    expect(screen.getByRole("heading", { name: "Calendar" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Holidays" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "SLA policy" })).toBeTruthy();
    expect((screen.getByLabelText("Calendar name") as HTMLInputElement).value).toBe("Head office");
    expect((screen.getByLabelText("Timezone") as HTMLSelectElement).value).toBe("Asia/Riyadh");
    for (const d of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]) {
      expect(screen.getByRole("button", { name: d })).toBeTruthy();
    }
    expect(screen.getByRole("button", { name: "Mon" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: "Sat" }).getAttribute("aria-pressed")).toBe("false");
    const atRisk = screen.getByLabelText("At risk when this many business days or fewer remain") as HTMLInputElement;
    expect(atRisk.step).toBe("0.1");
    expect(atRisk.min).toBe("0");
    const esc = screen.getByLabelText("Escalate after this many business days overdue") as HTMLInputElement;
    expect(esc.min).toBe("1");
    expect(esc.max).toBe("30");
    expect(screen.getByText("Between 1 and 30 business days.")).toBeTruthy();
  });

  it("lists holidays newest first with a Remove button each", () => {
    prime();
    render(<CalendarPage />);
    const items = screen.getAllByRole("listitem");
    expect(items[0]!.textContent).toContain("Year end");
    expect(items[0]!.textContent).toContain("25 Dec 2026");
    expect(items[1]!.textContent).toContain("1 Jan 2026");
    expect(screen.getByRole("button", { name: "Remove New Year" })).toBeTruthy();
  });

  it("saves the calendar as a full replace without holidays, with a success toast", () => {
    const m = prime();
    render(<CalendarPage />);
    fireEvent.click(screen.getByRole("button", { name: "Sat" }));
    fireEvent.click(screen.getByRole("button", { name: "Save calendar" }));
    expect(m.calendar).toHaveBeenCalledWith(
      { name: "Head office", timezone: "Asia/Riyadh", workingDays: [1, 2, 3, 4, 5, 6] },
      expect.anything(),
    );
    expect(show).toHaveBeenCalledWith("Calendar saved");
  });

  it("disables Save with a message when no working day is picked", () => {
    prime({ cal: { ...calendar, workingDays: [1] } });
    render(<CalendarPage />);
    fireEvent.click(screen.getByRole("button", { name: "Mon" }));
    expect(screen.getByText("Pick at least one working day.")).toBeTruthy();
    expect((screen.getByRole("button", { name: "Save calendar" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("shows the server detail when the timezone is rejected", () => {
    prime({ fail: { calendar: new ApiError(400, JSON.stringify({ detail: "Unknown timezone: Foo" })) } });
    render(<CalendarPage />);
    fireEvent.click(screen.getByRole("button", { name: "Save calendar" }));
    expect(screen.getByText("Unknown timezone: Foo")).toBeTruthy();
    expect(show).not.toHaveBeenCalled();
  });

  it("adds a holiday and reports a duplicate date as a 409", () => {
    const m = prime({ fail: { add: new ApiError(409, "{}") } });
    render(<CalendarPage />);
    fireEvent.change(screen.getByLabelText("Holiday date"), { target: { value: "2026-05-01" } });
    fireEvent.change(screen.getByLabelText("Holiday name"), { target: { value: "Labour Day" } });
    fireEvent.click(screen.getByRole("button", { name: "Add holiday" }));
    expect(m.add).toHaveBeenCalledWith({ date: "2026-05-01", name: "Labour Day" }, expect.anything());
    expect(screen.getByText("A holiday already exists on that date.")).toBeTruthy();
  });

  it("keeps Add holiday disabled until date and name are valid", () => {
    prime();
    render(<CalendarPage />);
    const add = screen.getByRole("button", { name: "Add holiday" }) as HTMLButtonElement;
    expect(add.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Holiday date"), { target: { value: "1999-05-01" } });
    fireEvent.change(screen.getByLabelText("Holiday name"), { target: { value: "X" } });
    expect(add.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Holiday date"), { target: { value: "2026-05-01" } });
    expect(add.disabled).toBe(false);
  });

  it("removes a holiday without confirming and toasts", () => {
    const confirm = vi.spyOn(window, "confirm");
    const m = prime();
    render(<CalendarPage />);
    fireEvent.click(screen.getByRole("button", { name: "Remove New Year" }));
    expect(m.remove).toHaveBeenCalledWith("h1", expect.anything());
    expect(show).toHaveBeenCalledWith("Holiday removed");
    expect(confirm).not.toHaveBeenCalled();
  });

  it("explains that escalation is mandatory", () => {
    prime();
    render(<CalendarPage />);
    expect(
      screen.getByText(
        "Escalation to a manager is mandatory and cannot be turned off; these numbers only set when it happens.",
      ),
    ).toBeTruthy();
  });

  it("saves the policy with both fields even when only one changed", () => {
    const m = prime();
    render(<CalendarPage />);
    fireEvent.change(screen.getByLabelText("Escalate after this many business days overdue"), {
      target: { value: "5" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save policy" }));
    expect(m.policy).toHaveBeenCalledWith(
      { atRiskDays: 0.3, escalateAfterOverdueDays: 5 },
      expect.anything(),
    );
    expect(show).toHaveBeenCalledWith("SLA policy saved");
  });

  it("refuses an escalation ceiling above 30 but accepts 30", () => {
    prime();
    render(<CalendarPage />);
    const esc = screen.getByLabelText("Escalate after this many business days overdue");
    const save = screen.getByRole("button", { name: "Save policy" }) as HTMLButtonElement;
    fireEvent.change(esc, { target: { value: "31" } });
    expect(save.disabled).toBe(true);
    expect(screen.getByText("Enter a whole number from 1 to 30.")).toBeTruthy();
    fireEvent.change(esc, { target: { value: "30" } });
    expect(save.disabled).toBe(false);
  });

  it("refuses an at-risk value off the 0.1 grid", () => {
    prime();
    render(<CalendarPage />);
    fireEvent.change(screen.getByLabelText("At risk when this many business days or fewer remain"), {
      target: { value: "0.25" },
    });
    expect((screen.getByRole("button", { name: "Save policy" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("shows skeletons while loading", () => {
    prime({ loading: true });
    render(<CalendarPage />);
    expect(screen.getAllByLabelText("Loading").length).toBeGreaterThan(0);
  });

  it("shows an error state on a failed load", () => {
    prime({ error: true });
    render(<CalendarPage />);
    expect(screen.getAllByRole("alert").length).toBeGreaterThan(0);
  });

  it("shows an empty state with zero holidays", () => {
    prime({ cal: { ...calendar, holidays: [] } });
    render(<CalendarPage />);
    const card = screen.getByRole("heading", { name: "Holidays" }).closest("div")!.parentElement!;
    expect(within(card).getByText("No holidays yet.")).toBeTruthy();
  });
});
