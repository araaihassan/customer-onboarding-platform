import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
// Registers toBeInTheDocument() etc. on vitest's expect. Nothing else in this
// suite has needed a jest-dom matcher yet, so it isn't wired into the global
// vitest config -- imported locally here rather than widening that config for
// a single test file.
import "@testing-library/jest-dom/vitest";
import { DataTable } from "./DataTable";

type Row = { id: string; name: string };
const rows: Row[] = [{ id: "1", name: "Acme" }, { id: "2", name: "Orbit" }];
const columns = [{ key: "name", label: "Name" }];

afterEach(cleanup);

describe("DataTable", () => {
  it("renders a header row and one row per item, wrapped in a horizontal-scroll container", () => {
    render(<DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} />);
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Acme")).toBeInTheDocument();
    expect(screen.getByText("Orbit")).toBeInTheDocument();
  });

  it("renders each row as a button when onRowClick is provided, and calls it with the row", () => {
    const onRowClick = vi.fn();
    const { container } = render(
      <DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} onRowClick={onRowClick} />,
    );
    // The clickable row carries `role="row"` (ARIA table structure, Finding 3),
    // which as an explicit role attribute wins over the element's implicit
    // "button" role for role-query purposes -- so the row is found by its
    // content rather than `getByRole("button")`.
    const acmeRow = within(container)
      .getAllByRole("row")
      .find((row) => row.tagName === "BUTTON" && row.textContent === "Acme")!;
    expect(acmeRow).toBeInstanceOf(HTMLButtonElement);
    acmeRow.click();
    expect(onRowClick).toHaveBeenCalledWith(rows[0]);
  });

  it("does not render rows as buttons when onRowClick is omitted", () => {
    render(<DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  describe("framed", () => {
    it("carries the card-frame classes by default", () => {
      const { container } = render(<DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} />);
      const tableWrapper = container.querySelector("[data-view='table']")!;
      expect(tableWrapper.className).toContain("bg-surface");
      expect(tableWrapper.className).toContain("border-line");
      expect(tableWrapper.className).toContain("rounded-11");
      expect(tableWrapper.className).toContain("overflow-hidden");
    });

    /**
     * A caller that already wraps DataTable in its own frame -- MigrationTable's
     * page does this today, and would otherwise end up double-bordered the
     * moment it converts to this primitive -- needs a way to opt out rather
     * than being forced into a second, redundant frame.
     */
    it("omits the card-frame classes when framed is false", () => {
      const { container } = render(
        <DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} framed={false} />,
      );
      const tableWrapper = container.querySelector("[data-view='table']")!;
      expect(tableWrapper.className).not.toContain("bg-surface");
      expect(tableWrapper.className).not.toContain("border-line");
      expect(tableWrapper.className).not.toContain("rounded-11");
      expect(tableWrapper.className).not.toContain("overflow-hidden");
    });
  });

  describe("stackedColumn", () => {
    it("renders no card list when stackedColumn is omitted", () => {
      const { container } = render(<DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} />);
      expect(container.querySelector("[data-view='cards']")).toBeNull();
    });

    /**
     * The same shape as the `MigrationTable` empty-state-guard bug named in
     * CLAUDE.md: a fallback that silently drops rows is a regression. Three
     * rows in, three cards out -- proven by count, not by checking the first
     * one and assuming the rest followed.
     */
    it("renders one card per row below 900px, dropping none", () => {
      const threeRows: Row[] = [...rows, { id: "3", name: "Halden" }];
      const { container } = render(
        <DataTable
          columns={columns}
          rows={threeRows}
          getRowKey={(r) => r.id}
          stackedColumn={(row) => <span>card:{row.name}</span>}
        />,
      );

      const cardsWrapper = container.querySelector("[data-view='cards']")!;
      expect(cardsWrapper).not.toBeNull();
      const cards = within(cardsWrapper as HTMLElement).getAllByRole("listitem");
      expect(cards).toHaveLength(3);
      expect(cards.map((c) => c.textContent)).toEqual(["card:Acme", "card:Orbit", "card:Halden"]);
    });

    it("keeps both presentations mounted, gated by responsive classes rather than conditional rendering", () => {
      const { container } = render(
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r) => r.id}
          stackedColumn={(row) => <span>card:{row.name}</span>}
        />,
      );

      const tableWrapper = container.querySelector("[data-view='table']")!;
      expect(tableWrapper.className).toContain("hidden");
      expect(tableWrapper.className).toContain("min-[900px]:block");

      const cardsWrapper = container.querySelector("[data-view='cards']")!;
      expect(cardsWrapper.className).toContain("min-[900px]:hidden");

      // Both are actually in the DOM at once -- jsdom applies no media
      // queries, so this is the only way to prove neither is conditionally
      // unmounted.
      expect(within(tableWrapper as HTMLElement).getByText("Acme")).not.toBeNull();
      expect(within(cardsWrapper as HTMLElement).getByText("card:Acme")).not.toBeNull();
    });
  });
});
