import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ToastProvider, useToast } from "./Toast";

afterEach(cleanup);

function TestButton() {
  const { show } = useToast();
  return <button onClick={() => show("Case migrated")}>Trigger</button>;
}

describe("Toast", () => {
  it("shows a message on demand and auto-dismisses after 2600ms", () => {
    vi.useFakeTimers();
    render(
      <ToastProvider>
        <TestButton />
      </ToastProvider>,
    );
    fireEvent.click(screen.getByText("Trigger"));
    expect(screen.getByText("Case migrated")).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(2600));
    expect(screen.queryByText("Case migrated")).not.toBeInTheDocument();
    vi.useRealTimers();
  });
});
