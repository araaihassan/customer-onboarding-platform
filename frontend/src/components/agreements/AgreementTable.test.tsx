import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import type { Agreement } from "@/lib/api/agreements";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { AgreementTable } = await import("./AgreementTable");

afterEach(cleanup);

const NOW = new Date("2026-09-20T10:00:00Z");

const signed: Agreement = {
  id: "ag-1",
  caseId: "case-1",
  customerId: "cust-1",
  customerName: "Acme Corp",
  name: "Master services agreement",
  recordMode: "FILE_BACKED",
  displayStatus: "SIGNED",
  latestVersionNumber: 3,
  signedAt: "2026-08-14T09:00:00Z",
  expiresAt: "2027-01-01",
  ownerUserId: "u-1",
};
const expiring: Agreement = { ...signed, id: "ag-3", name: "Renewal", expiresAt: "2026-10-02" };
const draft: Agreement = {
  id: "ag-2",
  caseId: "case-2",
  customerId: "cust-2",
  name: "Data processing addendum",
  recordMode: "STRUCTURED_ONLY",
  displayStatus: "DRAFT",
};

const grid = (c: HTMLElement) => c.querySelector("[data-view='table']") as HTMLElement;

describe("AgreementTable", () => {
  it("the table has Agreement / Customer / Record mode / Owner / Status columns on the 1.5fr 1fr 1.2fr .8fr 1.5fr grid", () => {
    const { container } = render(<AgreementTable agreements={[signed]} slug="acme" now={NOW} ownerNames={{}} />);
    const heads = within(grid(container)).getAllByRole("columnheader").map((h) => h.textContent);
    expect(heads).toEqual(["Agreement", "Customer", "Record mode", "Owner", "Status"]);
    const row = within(grid(container)).getAllByRole("row")[0] as HTMLElement;
    expect(row.style.gridTemplateColumns).toBe("1.5fr 1fr 1.2fr .8fr 1.5fr");
  });

  it("renders record-mode phrasing, customer name, owner, status chip and date phrases", () => {
    const { container } = render(
      <AgreementTable agreements={[signed, draft, expiring]} slug="acme" now={NOW} ownerNames={{ "u-1": "Dana Owner" }} />,
    );
    const g = within(grid(container));
    expect(g.getAllByText("File-backed · v3").length).toBe(2);
    expect(g.getByText("Structured record only")).toBeInTheDocument();
    expect(g.getAllByText("Acme Corp").length).toBe(2);
    expect(g.getAllByText("Dana Owner").length).toBe(2);
    expect(g.getAllByText("Signed").length).toBe(2);
    expect(g.getByText("Draft")).toBeInTheDocument();
    expect(g.getByText("signed 14 Aug")).toBeInTheDocument();
    expect(g.getByText("expires in 12d")).toBeInTheDocument();
  });

  it("renders an em dash for a missing customer name", () => {
    const { container } = render(<AgreementTable agreements={[draft]} slug="acme" now={NOW} ownerNames={{}} />);
    const row = within(grid(container)).getAllByRole("row")[1] as HTMLElement;
    expect(within(row).getByText("—")).toBeInTheDocument();
  });

  it("never renders a raw owner id when the name is unknown", () => {
    const { container } = render(<AgreementTable agreements={[signed]} slug="acme" now={NOW} ownerNames={{}} />);
    expect(grid(container).textContent).not.toContain("u-1");
  });

  it("a row links to the case Agreements tab with the agreement open", () => {
    const { container } = render(<AgreementTable agreements={[signed]} slug="acme" now={NOW} ownerNames={{}} />);
    const link = within(grid(container)).getByRole("link", { name: "Master services agreement" });
    expect(link.getAttribute("href")).toBe("/t/acme/customers/cust-1/cases/case-1?tab=agreements&agreement=ag-1");
  });

  it("falls back to a card list below 900px", () => {
    const { container } = render(<AgreementTable agreements={[signed]} slug="acme" now={NOW} ownerNames={{}} />);
    expect(grid(container).className).toContain("hidden min-[900px]:block");
    const cards = container.querySelector("[data-view='cards']") as HTMLElement;
    expect(cards.className).toContain("min-[900px]:hidden");
    expect(within(cards).getByText("Master services agreement")).toBeInTheDocument();
  });

  it("renders the empty state", () => {
    const { getByText } = render(<AgreementTable agreements={[]} slug="acme" now={NOW} ownerNames={{}} />);
    expect(getByText("No agreements")).toBeInTheDocument();
  });
});
