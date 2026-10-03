import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

vi.mock("next/navigation", () => ({
  usePathname: () => "/t/acme/admin/users",
  useParams: () => ({ slug: "acme" }),
}));

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions, user: null }) }));

const { default: AdminLayout } = await import("./layout");

afterEach(cleanup);
beforeEach(() => {
  permissions = { "user.view": ["ALL"], "role.view": ["ALL"] };
});

describe("admin tab strip", () => {
  it("has no Business calendar tab without calendar.manage", () => {
    render(<AdminLayout>x</AdminLayout>);
    expect(screen.queryByRole("link", { name: "Business calendar" })).toBeNull();
  });

  it("shows the Business calendar tab with calendar.manage", () => {
    permissions["calendar.manage"] = ["ALL"];
    render(<AdminLayout>x</AdminLayout>);
    const link = screen.getByRole("link", { name: "Business calendar" });
    expect(link.getAttribute("href")).toBe("/t/acme/admin/business-calendar");
  });
});
