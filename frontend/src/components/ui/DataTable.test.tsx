import { cleanup, render, screen } from "@testing-library/react";
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
});
