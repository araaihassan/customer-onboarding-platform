import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { PublishPanel } from "./PublishPanel";
import type { MigrationPreview } from "@/lib/api/workflows";

afterEach(cleanup);

const preview: MigrationPreview = {
  versionId: "v-5",
  onVersion: 31,
  eligible: 18,
  candidates: [],
};

describe("PublishPanel", () => {
  it("shows the two counts from the preview, as the prototype draws it", () => {
    render(
      <PublishPanel versionNo={4} slug="acme" templateId="t-1" versionId="v-5" preview={preview} isLoading={false} />,
    );

    expect(screen.getByText(/31 cases on v4/)).not.toBeNull();
    expect(screen.getByText(/18 eligible to migrate/)).not.toBeNull();
  });

  it("explains freeze-by-default before offering the button", () => {
    render(
      <PublishPanel versionNo={4} slug="acme" templateId="t-1" versionId="v-5" preview={preview} isLoading={false} />,
    );

    const explanation = screen.getByText(/stay frozen on their current version by default/i);
    const button = screen.getByRole("link", { name: /review migration/i });

    // DOCUMENT_POSITION_FOLLOWING: explanation comes first in the DOM.
    expect(
      explanation.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("links to the migration review screen for this version", () => {
    render(
      <PublishPanel versionNo={4} slug="acme" templateId="t-1" versionId="v-5" preview={preview} isLoading={false} />,
    );

    const link = screen.getByRole("link", { name: /review migration/i }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/t/acme/admin/workflows/t-1/migration?versionId=v-5");
  });

  it("renders nothing that claims counts while the preview is still loading", () => {
    render(
      <PublishPanel versionNo={4} slug="acme" templateId="t-1" versionId="v-5" preview={undefined} isLoading={true} />,
    );

    expect(screen.queryByText(/eligible to migrate/)).toBeNull();
  });
});
