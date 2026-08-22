import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Checkbox } from "./Checkbox";

afterEach(cleanup);

describe("Checkbox", () => {
  it("is a real input so screen readers and keyboards get it for free", () => {
    render(<Checkbox checked={false} onChange={vi.fn()} label="KYC pack received" />);

    const checkbox = screen.getByRole("checkbox", { name: "KYC pack received" });
    expect(checkbox.tagName).toBe("INPUT");
    expect((checkbox as HTMLInputElement).type).toBe("checkbox");
  });

  it("reports the checked attribute honestly", () => {
    render(<Checkbox checked={false} onChange={vi.fn()} label="x" />);
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(false);

    cleanup();
    render(<Checkbox checked={true} onChange={vi.fn()} label="x" />);
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(true);
  });

  it("calls onChange with the toggled value", () => {
    const onChange = vi.fn();
    render(<Checkbox checked={false} onChange={onChange} label="x" />);

    fireEvent.click(screen.getByRole("checkbox"));

    expect(onChange).toHaveBeenCalledWith(true);
  });

  /**
   * The title change must not be the only signal (component-specs §11's own
   * accessibility note) -- struck-through is a visual affordance layered on top
   * of the real checked state, never a replacement for it.
   */
  it("strikes the label through when checked", () => {
    render(<Checkbox checked={true} onChange={vi.fn()} label="Do it" />);
    expect(screen.getByText("Do it").style.textDecoration).toBe("line-through");
  });

  /**
   * busy is not cosmetic: satisfying a requirement is a server round-trip, and
   * a second click would fire a second mutation against state the first has
   * not returned yet.
   */
  it("stays disabled while a mutation is in flight", () => {
    render(<Checkbox checked={false} onChange={vi.fn()} label="x" busy />);
    expect((screen.getByRole("checkbox") as HTMLInputElement).disabled).toBe(true);
  });
});
