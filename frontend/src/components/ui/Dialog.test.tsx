import { useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Dialog } from "./Dialog";

afterEach(cleanup);

/**
 * A dialog opened from a real trigger, because half of what is under test here
 * is what happens to the trigger's focus.
 */
function Harness({ withHidden = false }: { withHidden?: boolean }) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Open
      </button>
      {open && (
        <Dialog title="Deactivate customer" onClose={() => setOpen(false)}>
          <button type="button">First</button>
          <button type="button">Second</button>
          {/* A control the interface has hidden — a collapsed section, a state
              that does not apply. It must not become an invisible tab stop. */}
          {withHidden && (
            <button type="button" style={{ display: "none" }}>
              Hidden
            </button>
          )}
        </Dialog>
      )}
    </>
  );
}

function open(withHidden = false) {
  render(<Harness withHidden={withHidden} />);
  // Focused before the click on purpose. A browser focuses a button when it is
  // activated — by Enter or Space, and by pointer in every engine but Safari —
  // but jsdom's synthetic click does not, so without this the opener is <body>
  // and the focus-restore assertion would be testing jsdom rather than Dialog.
  screen.getByRole("button", { name: "Open" }).focus();
  fireEvent.click(screen.getByRole("button", { name: "Open" }));
}

function button(name: string) {
  return screen.getByRole("button", { name });
}

describe("Dialog", () => {
  /**
   * Focus moves in on open, or a keyboard user is stranded behind a dialog they
   * cannot see the start of.
   */
  it("moves focus to the first control inside it", () => {
    open();
    expect(document.activeElement).toBe(button("First"));
  });

  /**
   * And back out on close. Focus dropped to <body> loses the user their place
   * entirely — on a long list, they are returned to the top of the document.
   */
  it("returns focus to whatever opened it", () => {
    open();
    fireEvent.keyDown(document, { key: "Escape" });

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(button("Open"));
  });

  it("closes on Escape", () => {
    open();
    expect(screen.getByRole("dialog", { name: "Deactivate customer" })).not.toBeNull();

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  /**
   * Tab must wrap. Without the trap, Tab from the last control walks into the
   * page behind the overlay, where the user is operating controls they cannot
   * see and cannot tell they have left the dialog.
   */
  it("wraps Tab from the last control back to the first", () => {
    open();
    button("Second").focus();

    fireEvent.keyDown(document, { key: "Tab" });
    expect(document.activeElement).toBe(button("First"));
  });

  it("wraps Shift+Tab from the first control back to the last", () => {
    open();
    button("First").focus();

    fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(button("Second"));
  });

  it("leaves Tab alone in the middle of the dialog", () => {
    open();
    button("First").focus();

    // Not at either end, so the browser's own tabbing is not intercepted.
    fireEvent.keyDown(document, { key: "Tab" });
    expect(document.activeElement).toBe(button("First"));
  });

  /**
   * A control hidden with display:none is not focusable in a browser, but it is
   * still matched by a plain querySelectorAll. Counting it makes it the trap's
   * last stop, so Tab from the last *visible* control appears to do nothing —
   * focus has gone somewhere the user cannot see or leave.
   */
  it("ignores a control the interface has hidden", () => {
    open(true);
    button("Second").focus();

    fireEvent.keyDown(document, { key: "Tab" });
    expect(document.activeElement).toBe(button("First"));
  });

  it("labels itself from its own title", () => {
    open();
    const dialog = screen.getByRole("dialog", { name: "Deactivate customer" });
    expect(dialog.getAttribute("aria-modal")).toBe("true");
  });
});
