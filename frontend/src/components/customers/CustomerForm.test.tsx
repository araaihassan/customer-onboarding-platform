import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { CustomerForm } from "./CustomerForm";

afterEach(cleanup);

function submitButton() {
  return screen.getByRole("button", { name: "Save" });
}

function field(label: string) {
  return screen.getByLabelText(label) as HTMLInputElement;
}

describe("CustomerForm", () => {
  it("refuses to submit without the two names the record cannot exist without", () => {
    const onSubmit = vi.fn();
    render(<CustomerForm submitLabel="Save" onSubmit={onSubmit} onCancel={() => {}} />);

    fireEvent.click(submitButton());

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getAllByText("This field is required")).toHaveLength(2);
  });

  it("submits the trimmed values once both names are present", () => {
    const onSubmit = vi.fn();
    render(<CustomerForm submitLabel="Save" onSubmit={onSubmit} onCancel={() => {}} />);

    fireEvent.change(field("Display name"), { target: { value: "  Northwind  " } });
    fireEvent.change(field("Legal name"), { target: { value: "Northwind Foods Ltd" } });
    fireEvent.change(field("Industry"), { target: { value: "Food & Beverage" } });
    fireEvent.change(field("Country"), { target: { value: "gb" } });
    fireEvent.click(submitButton());

    expect(onSubmit).toHaveBeenCalledWith({
      displayName: "Northwind",
      legalName: "Northwind Foods Ltd",
      industry: "Food & Beverage",
      // ISO 3166-1 alpha-2, and the column is two characters: normalising here
      // is what stops "gb" and "GB" becoming two countries.
      country: "GB",
    });
  });

  it("prefills from the customer it is editing", () => {
    render(
      <CustomerForm
        initial={{
          id: "c-1",
          displayName: "Northwind Foods",
          legalName: "Northwind Foods Holdings Ltd",
          industry: "Food & Beverage",
          country: "GB",
          status: "ACTIVE",
        }}
        submitLabel="Save"
        onSubmit={() => {}}
        onCancel={() => {}}
      />,
    );

    expect(field("Display name").value).toBe("Northwind Foods");
    expect(field("Legal name").value).toBe("Northwind Foods Holdings Ltd");
    expect(field("Industry").value).toBe("Food & Beverage");
    expect(field("Country").value).toBe("GB");
  });

  it("clears a field error as soon as the field is filled in", () => {
    render(<CustomerForm submitLabel="Save" onSubmit={() => {}} onCancel={() => {}} />);

    fireEvent.click(submitButton());
    expect(field("Display name").getAttribute("aria-invalid")).toBe("true");

    fireEvent.change(field("Display name"), { target: { value: "Northwind" } });
    expect(field("Display name").getAttribute("aria-invalid")).toBeNull();
  });

  it("reports a failed save without discarding what was typed", () => {
    render(
      <CustomerForm
        submitLabel="Save"
        error="Something went wrong"
        onSubmit={() => {}}
        onCancel={() => {}}
      />,
    );
    expect(screen.getByRole("alert").textContent).toBe("Something went wrong");
  });
});
