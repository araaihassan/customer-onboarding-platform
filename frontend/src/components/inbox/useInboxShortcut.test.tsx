import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render } from "@testing-library/react";

const { useInboxShortcut } = await import("./useInboxShortcut");

function Probe({ toggle, enabled }: { toggle: () => void; enabled?: boolean }) {
  useInboxShortcut(toggle, enabled);
  return <input aria-label="field" />;
}

afterEach(cleanup);

describe("useInboxShortcut", () => {
  it("toggles on meta+j and ctrl+j and prevents the browser default", () => {
    const toggle = vi.fn();
    render(<Probe toggle={toggle} />);
    const meta = new KeyboardEvent("keydown", { key: "j", metaKey: true, cancelable: true, bubbles: true });
    document.dispatchEvent(meta);
    expect(meta.defaultPrevented).toBe(true);
    fireEvent.keyDown(document, { key: "J", ctrlKey: true });
    expect(toggle).toHaveBeenCalledTimes(2);
  });

  it("ignores a plain j", () => {
    const toggle = vi.fn();
    render(<Probe toggle={toggle} />);
    fireEvent.keyDown(document, { key: "j" });
    expect(toggle).not.toHaveBeenCalled();
  });

  it("does not fire while typing in an input", () => {
    const toggle = vi.fn();
    const { getByLabelText } = render(<Probe toggle={toggle} />);
    fireEvent.keyDown(getByLabelText("field"), { key: "j", ctrlKey: true });
    expect(toggle).not.toHaveBeenCalled();
  });

  it("attaches no listener when disabled", () => {
    const toggle = vi.fn();
    render(<Probe toggle={toggle} enabled={false} />);
    fireEvent.keyDown(document, { key: "j", ctrlKey: true });
    expect(toggle).not.toHaveBeenCalled();
  });
});
