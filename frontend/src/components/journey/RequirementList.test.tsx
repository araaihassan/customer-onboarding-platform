import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { RequirementRoadmap } from "@/lib/api/cases";
import { RequirementList } from "./RequirementList";

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

// `vi.mock` factories are hoisted above every other statement, including a
// plain `const` -- `vi.hoisted` is what makes a value the factory closes
// over survive that hoist, matching `DocumentsTab.test.tsx`'s own precedent
// for mocking `downloadDocumentVersion` without losing the shared instance.
const { downloadDocumentVersion } = vi.hoisted(() => ({ downloadDocumentVersion: vi.fn() }));
vi.mock("@/lib/api/documents", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/documents")>();
  return { ...actual, downloadDocumentVersion };
});

afterEach(cleanup);

const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function makeWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

const open: RequirementRoadmap = { id: "r-1", label: "Collect ID", kind: "MANUAL", mandatory: true, status: "OPEN" };
const document: RequirementRoadmap = { id: "r-2", label: "Passport scan", kind: "DOCUMENT", mandatory: true, status: "OPEN" };
const satisfiedByDocument: RequirementRoadmap = {
  id: "r-3",
  label: "Tax certificate",
  kind: "DOCUMENT",
  mandatory: true,
  status: "SATISFIED",
  satisfiedRef: "doc-1",
  satisfiedRefType: "document",
};

function renderList(requirements: RequirementRoadmap[]) {
  return render(<RequirementList caseId="c-1" milestoneId="m-1" requirements={requirements} />, {
    wrapper: makeWrapper(),
  });
}

beforeEach(() => {
  permissions = { "milestone.complete": ["ALL"], "requirement.waive": ["ALL"] };
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(reply({ id: "r-1", status: "SATISFIED" }));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
  downloadDocumentVersion.mockReset();
});

describe("RequirementList", () => {
  /**
   * The one deliberate departure from prototype behaviour. uispecs says
   * checkbox state is "real and local"; here it cannot be -- satisfying a
   * requirement recomputes the milestone, possibly the stage transition and
   * the case percentage, all server-side.
   */
  it("waits for the server rather than flipping locally", async () => {
    let resolveSatisfy!: (value: Response) => void;
    fetchMock.mockReturnValueOnce(new Promise((resolve) => { resolveSatisfy = resolve; }));

    renderList([open]);
    fireEvent.click(screen.getByRole("checkbox"));

    expect((screen.getByRole("checkbox") as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(false);

    resolveSatisfy(reply({ id: "r-1", status: "SATISFIED" }));
    await waitFor(() => expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(true));
  });

  it("renders a write-scope 403 as an explanation, not a disappearance", async () => {
    fetchMock.mockResolvedValueOnce(
      reply({ detail: "Stage \"Legal Review\" write scope OWNER_ONLY does not admit the caller" }, 403),
    );

    renderList([open]);
    fireEvent.click(screen.getByRole("checkbox"));

    await waitFor(() => expect(screen.getByText(/write scope OWNER_ONLY does not admit/)).not.toBeNull());
    expect(screen.getByText("Collect ID")).not.toBeNull();
  });

  it("renders DOCUMENT requirements as the design's document chips, not a checkbox", () => {
    renderList([document]);
    expect(screen.queryByRole("checkbox")).toBeNull();
    expect(screen.getByText("Passport scan")).not.toBeNull();
  });

  it("does not fetch or show a document link for a DOCUMENT requirement with no satisfiedRef yet", () => {
    renderList([document]);
    expect(screen.queryByRole("button", { name: /open/i })).toBeNull();
    expect(fetchMock).not.toHaveBeenCalledWith(expect.stringContaining("/documents/"), expect.anything());
  });

  it("fetches and links the document that satisfied a DOCUMENT requirement", async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url.includes("/documents/doc-1")) {
        return Promise.resolve(
          reply({ id: "doc-1", name: "Tax Certificate 2026.pdf", currentVersionId: "v-1", currentVersionNumber: 1 }),
        );
      }
      return Promise.resolve(reply({ id: "r-1", status: "SATISFIED" }));
    });

    renderList([satisfiedByDocument]);

    await waitFor(() => expect(screen.getByText("Tax Certificate 2026.pdf")).not.toBeNull());
    expect(screen.getByRole("button", { name: /open/i })).not.toBeNull();
  });

  it("downloads the linked document's current version when Open is clicked", async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url.includes("/documents/doc-1")) {
        return Promise.resolve(
          reply({ id: "doc-1", name: "Tax Certificate 2026.pdf", currentVersionId: "v-1", currentVersionNumber: 1 }),
        );
      }
      return Promise.resolve(reply({ id: "r-1", status: "SATISFIED" }));
    });

    renderList([satisfiedByDocument]);

    await waitFor(() => expect(screen.getByRole("button", { name: /open/i })).not.toBeNull());
    fireEvent.click(screen.getByRole("button", { name: /open/i }));

    await waitFor(() =>
      expect(downloadDocumentVersion).toHaveBeenCalledWith("doc-1", 1, "Tax Certificate 2026.pdf"),
    );
  });

  it("hides waive without requirement.waive", () => {
    permissions = { "milestone.complete": ["ALL"] };
    renderList([open]);
    expect(screen.queryByRole("button", { name: /waive/i })).toBeNull();
  });

  it("shows an Upload button for an open DOCUMENT requirement with a matching open document request, when the user holds document.upload", async () => {
    permissions = { "milestone.complete": ["ALL"], "requirement.waive": ["ALL"], "document.upload": ["ALL"] };
    fetchMock.mockImplementation((url: string) => {
      if (url.includes("/document-requests")) {
        return Promise.resolve(
          reply({ content: [{ id: "dr-1", requirementId: "r-2", status: "OPEN", category: "TAX", description: "Passport scan" }] }),
        );
      }
      return Promise.resolve(reply({ id: "r-1", status: "SATISFIED" }));
    });

    renderList([document]);

    await waitFor(() => expect(screen.getByRole("button", { name: "Upload" })).not.toBeNull());
  });

  it("hides the Upload button without document.upload even with a matching open request", async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url.includes("/document-requests")) {
        return Promise.resolve(
          reply({ content: [{ id: "dr-1", requirementId: "r-2", status: "OPEN", category: "TAX", description: "Passport scan" }] }),
        );
      }
      return Promise.resolve(reply({ id: "r-1", status: "SATISFIED" }));
    });

    renderList([document]);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByRole("button", { name: "Upload" })).toBeNull();
  });

  it("uploading through the chip's dialog fulfils the matching document request with the new document's id", async () => {
    permissions = { "milestone.complete": ["ALL"], "requirement.waive": ["ALL"], "document.upload": ["ALL"] };
    const fulfilCalls: unknown[] = [];
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes("/document-requests/dr-1/fulfil")) {
        fulfilCalls.push(init?.body);
        return Promise.resolve(reply({ id: "dr-1", caseId: "c-1", status: "FULFILLED" }));
      }
      if (url.includes("/document-requests")) {
        return Promise.resolve(
          reply({ content: [{ id: "dr-1", requirementId: "r-2", status: "OPEN", category: "TAX", description: "Passport scan" }] }),
        );
      }
      if (url.endsWith("/documents") && init?.method === "POST") {
        return Promise.resolve(reply({ id: "doc-9", name: "Passport scan" }, 201));
      }
      return Promise.resolve(reply({ id: "r-1", status: "SATISFIED" }));
    });

    renderList([document]);

    await waitFor(() => expect(screen.getByRole("button", { name: "Upload" })).not.toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Upload" }));

    const dialog = within(screen.getByRole("dialog"));
    const file = new File(["bytes"], "passport.pdf", { type: "application/pdf" });
    fireEvent.change(dialog.getByLabelText(/file/i), { target: { files: [file] } });
    fireEvent.click(dialog.getByRole("button", { name: "Upload" }));

    await waitFor(() => expect(fulfilCalls).toHaveLength(1));
    expect(JSON.parse(fulfilCalls[0] as string)).toEqual({ documentId: "doc-9" });
  });

  it("shows Pending review, not Upload, once the matching request is fulfilled but the requirement is still open", async () => {
    permissions = { "milestone.complete": ["ALL"], "requirement.waive": ["ALL"], "document.upload": ["ALL"] };
    fetchMock.mockImplementation((url: string) => {
      if (url.includes("/document-requests")) {
        return Promise.resolve(
          reply({ content: [{ id: "dr-1", requirementId: "r-2", status: "FULFILLED", category: "TAX", description: "Passport scan" }] }),
        );
      }
      return Promise.resolve(reply({ id: "r-1", status: "SATISFIED" }));
    });

    renderList([document]);

    await waitFor(() => expect(screen.getByText("Pending review")).not.toBeNull());
    expect(screen.queryByRole("button", { name: "Upload" })).toBeNull();
  });

  it("offers waive to someone holding requirement.waive", () => {
    renderList([open]);
    expect(screen.getByRole("button", { name: /waive/i })).not.toBeNull();
  });
});
