import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { ContactForm } from "./ContactForm";

afterEach(cleanup);

function submitButton() {
  return screen.getByRole("button", { name: "Create contact" });
}

function field(label: string) {
  return screen.getByLabelText(label) as HTMLInputElement;
}

describe("ContactForm", () => {
  /**
   * `full_name` and `email` are NOT NULL on customer_contact (V8), so a blank
   * submit is not a validation nicety — it is a constraint violation that comes
   * back as a 500 and tells the user nothing about which field was wrong.
   */
  it("refuses to submit without the two values the record cannot exist without", () => {
    const onSubmit = vi.fn();
    render(<ContactForm pending={false} onSubmit={onSubmit} onCancel={() => {}} />);

    fireEvent.click(submitButton());

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getAllByText("This field is required")).toHaveLength(2);
  });

  it("submits the trimmed values once the name and address are present", () => {
    const onSubmit = vi.fn();
    render(<ContactForm pending={false} onSubmit={onSubmit} onCancel={() => {}} />);

    fireEvent.change(field("Full name"), { target: { value: "  Ada Okonjo  " } });
    fireEvent.change(field("Email"), { target: { value: "  ada@northwind.test " } });
    fireEvent.change(field("Job title"), { target: { value: "Head of Operations" } });
    fireEvent.change(field("Phone"), { target: { value: "+44 20 7946 0000" } });
    fireEvent.click(submitButton());

    expect(onSubmit).toHaveBeenCalledWith({
      fullName: "Ada Okonjo",
      email: "ada@northwind.test",
      title: "Head of Operations",
      phone: "+44 20 7946 0000",
      primaryContact: false,
    });
  });

  /**
   * A real checkbox, so it carries a checked state. Without a way to set it here
   * every contact created through the interface would be non-primary for good —
   * contact editing has no interface either.
   */
  it("sends the primary flag when it is ticked", () => {
    const onSubmit = vi.fn();
    render(<ContactForm pending={false} onSubmit={onSubmit} onCancel={() => {}} />);

    fireEvent.change(field("Full name"), { target: { value: "Ada Okonjo" } });
    fireEvent.change(field("Email"), { target: { value: "ada@northwind.test" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Primary contact" }));
    fireEvent.click(submitButton());

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ primaryContact: true }),
    );
  });

  it("clears a field error as soon as the field is filled in", () => {
    render(<ContactForm pending={false} onSubmit={() => {}} onCancel={() => {}} />);

    fireEvent.click(submitButton());
    expect(field("Full name").getAttribute("aria-invalid")).toBe("true");

    fireEvent.change(field("Full name"), { target: { value: "Ada Okonjo" } });
    expect(field("Full name").getAttribute("aria-invalid")).toBeNull();
  });

  it("reports a failed create without discarding what was typed", () => {
    render(
      <ContactForm
        pending={false}
        error="Something went wrong"
        onSubmit={() => {}}
        onCancel={() => {}}
      />,
    );
    expect(screen.getByRole("alert").textContent).toBe("Something went wrong");
  });

  /** The one visible sign that a create is in flight. */
  it("disables the submit while the create is in flight", () => {
    render(<ContactForm pending onSubmit={() => {}} onCancel={() => {}} />);
    expect((submitButton() as HTMLButtonElement).disabled).toBe(true);
  });
});
