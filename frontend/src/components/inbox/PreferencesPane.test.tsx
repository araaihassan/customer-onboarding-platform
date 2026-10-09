import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";

const mutate = vi.fn();
const show = vi.fn();
const refetch = vi.fn();
let prefs: Record<string, unknown>;
vi.mock("@/lib/api/notifications", () => ({
  usePreferences: () => prefs,
  useUpdatePreferences: () => ({ mutate }),
}));
vi.mock("@/components/ui/Toast", () => ({ useToast: () => ({ show }) }));

const { PreferencesPane } = await import("./PreferencesPane");

const TYPES = [
  { type: "ESCALATION", label: "Escalation to my manager", inApp: true, email: true, locked: true },
  { type: "TASK_ASSIGNED", label: "Task assigned to me", inApp: true, email: false, locked: false },
  { type: "NEW_COMMENT", label: "New comment", inApp: false, email: true, locked: false },
];

beforeEach(() => {
  prefs = { data: { emailCadence: "IMMEDIATE", types: TYPES }, isLoading: false, isError: false, refetch };
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("PreferencesPane", () => {
  it("renders the intro and the cadence tabs with the current one selected", () => {
    render(<PreferencesPane />);
    expect(screen.getByText(/Every notification type is optional/)).toBeInTheDocument();
    expect(screen.getAllByRole("tab")).toHaveLength(3);
    expect(screen.getByRole("tab", { name: "Immediately" })).toHaveAttribute("aria-selected", "true");
  });

  it("renders a row per type, locked switches disabled with the policy note", () => {
    render(<PreferencesPane />);
    expect(screen.getAllByRole("switch")).toHaveLength(6);
    expect(screen.getByText("Required by policy")).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "Email for Escalation to my manager" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("switch", { name: "In-app for Escalation to my manager" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("switch", { name: "Email for Task assigned to me" })).not.toHaveAttribute("aria-disabled");
  });

  it("toggling one switch sends the full body, never a partial", () => {
    render(<PreferencesPane />);
    fireEvent.click(screen.getByRole("switch", { name: "Email for Task assigned to me" }));
    expect(mutate).toHaveBeenCalledTimes(1);
    expect(mutate.mock.calls[0]![0]).toEqual({
      emailCadence: "IMMEDIATE",
      types: [
        { type: "TASK_ASSIGNED", inApp: true, email: true },
        { type: "NEW_COMMENT", inApp: false, email: true },
      ],
    });
  });

  it("choosing Daily digest sends DAILY", () => {
    render(<PreferencesPane />);
    fireEvent.click(screen.getByRole("tab", { name: "Daily digest" }));
    expect(mutate.mock.calls[0]![0].emailCadence).toBe("DAILY");
    expect(mutate.mock.calls[0]![0].types).toHaveLength(2);
  });

  it("a failed save shows a toast", () => {
    render(<PreferencesPane />);
    fireEvent.click(screen.getByRole("switch", { name: "In-app for New comment" }));
    const opts = mutate.mock.calls[0]![1] as { onError: () => void };
    opts.onError();
    expect(show).toHaveBeenCalledWith("Your change could not be saved");
  });

  it("shows an error state with retry", () => {
    prefs = { data: undefined, isLoading: false, isError: true, refetch };
    render(<PreferencesPane />);
    fireEvent.click(screen.getByRole("button"));
    expect(refetch).toHaveBeenCalled();
  });
});
