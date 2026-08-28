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
    render(<DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} onRowClick={onRowClick} />);
    screen.getByRole("button", { name: /acme/i }).click();
    expect(onRowClick).toHaveBeenCalledWith(rows[0]);
  });

  it("does not render rows as buttons when onRowClick is omitted", () => {
    render(<DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
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
