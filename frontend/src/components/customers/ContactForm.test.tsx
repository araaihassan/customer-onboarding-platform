import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { Contact } from "@/lib/api/customers";
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
    render(
      <ContactForm
        submitLabel="Create contact"
        pending={false}
        onSubmit={onSubmit}
        onCancel={() => {}}
      />,
    );

    fireEvent.click(submitButton());

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getAllByText("This field is required")).toHaveLength(2);
  });

  it("submits the trimmed values once the name and address are present", () => {
    const onSubmit = vi.fn();
    render(
      <ContactForm
        submitLabel="Create contact"
        pending={false}
        onSubmit={onSubmit}
        onCancel={() => {}}
      />,
    );

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
      // Reported, but not chosen: the form has no status control when creating
      // (asserted below) and the service sets ACTIVE regardless. ContactList
      // drops it when building the CreateContactRequest, which has no such
      // field.
      status: "ACTIVE",
    });
  });

  /**
   * A real checkbox, so it carries a checked state. Without a way to set it here
   * every contact created through the interface would be non-primary for good —
   * contact editing has no interface either.
   */
  it("sends the primary flag when it is ticked", () => {
    const onSubmit = vi.fn();
    render(
      <ContactForm
        submitLabel="Create contact"
        pending={false}
        onSubmit={onSubmit}
        onCancel={() => {}}
      />,
    );

    fireEvent.change(field("Full name"), { target: { value: "Ada Okonjo" } });
    fireEvent.change(field("Email"), { target: { value: "ada@northwind.test" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Primary contact" }));
    fireEvent.click(submitButton());

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ primaryContact: true }),
    );
  });

  it("clears a field error as soon as the field is filled in", () => {
    render(
      <ContactForm
        submitLabel="Create contact"
        pending={false}
        onSubmit={() => {}}
        onCancel={() => {}}
      />,
    );

    fireEvent.click(submitButton());
    expect(field("Full name").getAttribute("aria-invalid")).toBe("true");

    fireEvent.change(field("Full name"), { target: { value: "Ada Okonjo" } });
    expect(field("Full name").getAttribute("aria-invalid")).toBeNull();
  });

  it("reports a failed create without discarding what was typed", () => {
    render(
      <ContactForm
        submitLabel="Create contact"
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
    render(
      <ContactForm submitLabel="Create contact" pending onSubmit={() => {}} onCancel={() => {}} />,
    );
    expect((submitButton() as HTMLButtonElement).disabled).toBe(true);
  });

  /**
   * The service sets status = ACTIVE on create and ignores anything the caller
   * says about it, so a status control on the create form would be a lie — a
   * control that cannot affect the outcome.
   */
  it("offers no status control when creating", () => {
    render(
      <ContactForm submitLabel="Create contact" onSubmit={() => {}} onCancel={() => {}} />,
    );
    expect(screen.queryByLabelText("Status")).toBeNull();
  });
});

const existing: Contact = {
  id: "p-1",
  customerId: "c-1",
  fullName: "Ada Okonjo",
  email: "ada@northwind.test",
  title: "Head of Operations",
  phone: "+44 20 7946 0000",
  primaryContact: true,
  status: "ACTIVE",
};

describe("ContactForm: editing", () => {
  function renderEdit(onSubmit = vi.fn()) {
    render(
      <ContactForm
        initial={existing}
        submitLabel="Save"
        onSubmit={onSubmit}
        onCancel={() => {}}
      />,
    );
    return onSubmit;
  }

  it("prefills every field from the contact it is editing", () => {
    renderEdit();
    expect(field("Full name").value).toBe("Ada Okonjo");
    expect(field("Email").value).toBe("ada@northwind.test");
    expect(field("Job title").value).toBe("Head of Operations");
    expect(field("Phone").value).toBe("+44 20 7946 0000");
    expect((screen.getByRole("checkbox", { name: "Primary contact" }) as HTMLInputElement).checked)
      .toBe(true);
  });

  /**
   * DELETE is deny-by-default at the database layer and business records are
   * deactivated rather than deleted, so status = INACTIVE is the ONLY retirement
   * a contact has. Without this control a contact once created is permanent.
   */
  it("offers the retirement control when editing", () => {
    renderEdit();
    expect((screen.getByLabelText("Status") as HTMLSelectElement).value).toBe("ACTIVE");
  });

  it("submits every field the full-replace PUT accepts, including the new status", () => {
    const onSubmit = renderEdit();

    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "INACTIVE" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    // Exactly the six fields UpdateContactRequest accepts. A field missing from a
    // full replace is a field blanked, which is how Task 27 and Task R1 each lost
    // one.
    expect(onSubmit).toHaveBeenCalledWith({
      fullName: "Ada Okonjo",
      email: "ada@northwind.test",
      title: "Head of Operations",
      phone: "+44 20 7946 0000",
      primaryContact: true,
      status: "INACTIVE",
    });
  });
});
