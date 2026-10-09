import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Switch } from "./Switch";

afterEach(cleanup);

describe("Switch", () => {
  it("is a button with role switch and aria-checked, not a styled div", () => {
    render(<Switch checked={false} onChange={vi.fn()} label="Auto-advance" />);

    const el = screen.getByRole("switch", { name: "Auto-advance" });
    expect(el.tagName).toBe("BUTTON");
    expect(el.getAttribute("aria-checked")).toBe("false");
  });

  it("reflects checked in aria-checked", () => {
    render(<Switch checked={true} onChange={vi.fn()} label="Auto-advance" />);
    expect(screen.getByRole("switch").getAttribute("aria-checked")).toBe("true");
  });

  it("calls onChange with the flipped value on click", () => {
    const onChange = vi.fn();
    render(<Switch checked={false} onChange={onChange} label="Auto-advance" />);

    fireEvent.click(screen.getByRole("switch"));

    expect(onChange).toHaveBeenCalledWith(true);
  });

  /**
   * A switch with an accessible name but no visible text is a control a
   * sighted user cannot identify -- caught by actually looking at the
   * rendered Inspector, not by a unit test that only checked aria-label.
   */
  it("renders the label as real, visible text", () => {
    render(<Switch checked={false} onChange={vi.fn()} label="Auto-advance" />);
    expect(screen.getByText("Auto-advance")).not.toBeNull();
  });

  it("disabled blocks onChange and sets aria-disabled", () => {
    const onChange = vi.fn();
    render(<Switch checked={true} onChange={onChange} label="Email" disabled />);
    const el = screen.getByRole("switch");
    fireEvent.click(el);
    expect(onChange).not.toHaveBeenCalled();
    expect(el.getAttribute("aria-disabled")).toBe("true");
  });

  it("ariaLabel replaces aria-labelledby as the accessible name", () => {
    render(<Switch checked={false} onChange={vi.fn()} label="Email" ariaLabel="Email for Task assigned to me" />);
    const el = screen.getByRole("switch", { name: "Email for Task assigned to me" });
    expect(el.getAttribute("aria-labelledby")).toBeNull();
    expect(screen.getByText("Email")).not.toBeNull();
  });

  it("the knob carries the COMPONENTS 17 shadow", () => {
    render(<Switch checked={false} onChange={vi.fn()} label="Auto-advance" />);
    const knob = screen.getByRole("switch").querySelector("span") as HTMLElement;
    expect(knob.style.boxShadow).toBe("0 1px 2px rgba(0,0,0,.2)");
  });
});
