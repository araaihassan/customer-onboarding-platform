import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Tabs, type TabItem } from "./Tabs";

afterEach(cleanup);

const items: TabItem[] = [
  { id: "journey", label: "Journey" },
  { id: "tasks", label: "Tasks", badge: 8 },
  { id: "documents", label: "Documents", badge: 8 },
];

function Controlled({ initial = "journey" }: { initial?: string }) {
  const [value, setValue] = useState(initial);
  return <Tabs items={items} value={value} onChange={setValue} />;
}

describe("Tabs", () => {
  it("exposes tablist/tab semantics with the current selection marked", () => {
    render(<Tabs items={items} value="journey" onChange={vi.fn()} />);

    expect(screen.getByRole("tablist")).not.toBeNull();
    const selected = screen.getByRole("tab", { selected: true });
    expect(selected.textContent).toContain("Journey");
    expect(screen.getAllByRole("tab")).toHaveLength(3);
  });

  it("moves selection to the next tab on ArrowRight", () => {
    const onChange = vi.fn();
    render(<Tabs items={items} value="journey" onChange={onChange} />);

    fireEvent.keyDown(screen.getByRole("tab", { name: /Journey/ }), { key: "ArrowRight" });

    expect(onChange).toHaveBeenCalledWith("tasks");
  });

  it("wraps from the last tab back to the first on ArrowRight", () => {
    const onChange = vi.fn();
    render(<Tabs items={items} value="documents" onChange={onChange} />);

    fireEvent.keyDown(screen.getByRole("tab", { name: /Documents/ }), { key: "ArrowRight" });

    expect(onChange).toHaveBeenCalledWith("journey");
  });

  it("wraps from the first tab back to the last on ArrowLeft", () => {
    const onChange = vi.fn();
    render(<Tabs items={items} value="journey" onChange={onChange} />);

    fireEvent.keyDown(screen.getByRole("tab", { name: /Journey/ }), { key: "ArrowLeft" });

    expect(onChange).toHaveBeenCalledWith("documents");
  });

  it("actually switches the selected tab end to end", () => {
    render(<Controlled />);

    fireEvent.keyDown(screen.getByRole("tab", { name: /Journey/ }), { key: "ArrowRight" });

    expect(screen.getByRole("tab", { selected: true }).textContent).toContain("Tasks");
  });

  /** "Tasks (8)" must announce as "Tasks" -- the count is decorative, not the name. */
  it("renders a count badge without making it part of the accessible name", () => {
    render(<Tabs items={items} value="journey" onChange={vi.fn()} />);

    expect(screen.getByRole("tab", { name: "Tasks" })).not.toBeNull();
    expect(screen.queryByRole("tab", { name: /Tasks.*8/ })).toBeNull();
  });

  it("selects on click", () => {
    const onChange = vi.fn();
    render(<Tabs items={items} value="journey" onChange={onChange} />);

    fireEvent.click(screen.getByRole("tab", { name: "Documents" }));

    expect(onChange).toHaveBeenCalledWith("documents");
  });
});
