import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { caseKeys } from "./cases";
import {
  agreementKeys,
  useAgreement,
  useAgreements,
  useAgreementSummary,
  useCancelAgreement,
  useCaseAgreements,
  usePatchAgreement,
  useRecordSignature,
  useReplaceSignatories,
  useReviewAgreement,
  useSendAgreement,
  useSubmitAgreement,
  useUploadAgreementFile,
} from "./agreements";

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
  return {
    client,
    Wrapper: function Wrapper({ children }: { children: ReactNode }) {
      return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    },
  };
}

const lastUrl = () => fetchMock.mock.calls.at(-1)![0] as string;
const lastInit = () => fetchMock.mock.calls.at(-1)![1] as RequestInit;
const lastJson = () => JSON.parse(lastInit().body as string);

/** A write's response: the agreement detail wrapper. */
const detail = (id = "a1", caseId = "c1") => ({ agreement: { id, caseId }, signatories: [], versions: [], signatures: [] });

/** Every key passed to invalidateQueries, as JSON for comparison. */
function invalidated(spy: { mock: { calls: unknown[][] } }): string[] {
  return spy.mock.calls.map((c) => JSON.stringify((c[0] as { queryKey: unknown }).queryKey));
}
const keys = (...ks: readonly (readonly unknown[])[]) => ks.map((k) => JSON.stringify(k));

const STANDARD = [agreementKeys.detail("a1"), agreementKeys.forCase("c1"), agreementKeys.index(), agreementKeys.summary()];

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

async function runMutation<V>(
  hook: () => { mutateAsync: (v: V) => Promise<unknown> },
  vars: V,
  response: unknown = detail(),
) {
  fetchMock.mockResolvedValue(reply(response));
  const { Wrapper, client } = makeWrapper();
  const spy = vi.spyOn(client, "invalidateQueries");
  const { result } = renderHook(hook, { wrapper: Wrapper });
  await act(async () => {
    await result.current.mutateAsync(vars);
  });
  return invalidated(spy);
}

describe("agreements api reads", () => {
  it("useCaseAgreements reads /cases/{caseId}/agreements", async () => {
    fetchMock.mockResolvedValue(reply([]));
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useCaseAgreements("c1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(lastUrl()).toBe("/api/t/acme/cases/c1/agreements");
  });

  it("useAgreement reads /agreements/{id}", async () => {
    fetchMock.mockResolvedValue(reply(detail()));
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useAgreement("a1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(lastUrl()).toBe("/api/t/acme/agreements/a1");
  });

  it("useAgreements passes status and page", async () => {
    fetchMock.mockResolvedValue(reply({ content: [] }));
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useAgreements("EXPIRED", 0), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(lastUrl()).toBe("/api/t/acme/agreements?page=0&size=25&status=EXPIRED");
  });

  it("useAgreements omits status when unfiltered", async () => {
    fetchMock.mockResolvedValue(reply({ content: [] }));
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useAgreements(undefined, 2), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(lastUrl()).toBe("/api/t/acme/agreements?page=2&size=25");
  });

  it("useAgreementSummary reads /agreements/summary", async () => {
    fetchMock.mockResolvedValue(reply({}));
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useAgreementSummary(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(lastUrl()).toBe("/api/t/acme/agreements/summary");
  });
});

describe("agreements api writes", () => {
  it("usePatchAgreement PATCHes and invalidates detail, case list, index and summary", async () => {
    const body = { name: "MSA", lockVersion: 3, clear: ["EXPIRES_AT" as const] };
    const inv = await runMutation(usePatchAgreement, { id: "a1", body });
    expect(lastUrl()).toBe("/api/t/acme/agreements/a1");
    expect(lastInit().method).toBe("PATCH");
    expect(lastJson()).toEqual(body);
    expect(inv).toEqual(keys(...STANDARD));
  });

  it("useReplaceSignatories PUTs the whole list", async () => {
    const signatories = [{ kind: "CONTACT" as const, contactId: "k1", displayRole: "CEO" }];
    const inv = await runMutation(useReplaceSignatories, { id: "a1", signatories, lockVersion: 4 });
    expect(lastUrl()).toBe("/api/t/acme/agreements/a1/signatories");
    expect(lastInit().method).toBe("PUT");
    expect(lastJson()).toEqual({ signatories, lockVersion: 4 });
    expect(inv).toEqual(keys(...STANDARD));
  });

  it("useUploadAgreementFile posts multipart with lockVersion in the query", async () => {
    const file = new File(["x"], "a.pdf", { type: "application/pdf" });
    const inv = await runMutation(useUploadAgreementFile, { id: "a1", file, lockVersion: 5 });
    expect(lastUrl()).toBe("/api/t/acme/agreements/a1/file?lockVersion=5");
    expect(lastInit().method).toBe("POST");
    const body = lastInit().body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect((body.get("file") as File).name).toBe("a.pdf");
    expect(new Headers(lastInit().headers).has("Content-Type")).toBe(false);
    expect(inv).toEqual(keys(...STANDARD));
  });

  it("useSubmitAgreement posts {lockVersion}", async () => {
    const inv = await runMutation(useSubmitAgreement, { id: "a1", lockVersion: 6 });
    expect(lastUrl()).toBe("/api/t/acme/agreements/a1/submit");
    expect(lastInit().method).toBe("POST");
    expect(lastJson()).toEqual({ lockVersion: 6 });
    expect(inv).toEqual(keys(...STANDARD));
  });

  it("useSendAgreement posts {lockVersion}", async () => {
    const inv = await runMutation(useSendAgreement, { id: "a1", lockVersion: 7 });
    expect(lastUrl()).toBe("/api/t/acme/agreements/a1/send");
    expect(lastInit().method).toBe("POST");
    expect(lastJson()).toEqual({ lockVersion: 7 });
    expect(inv).toEqual(keys(...STANDARD));
  });

  it("useReviewAgreement posts to /versions/{n}/review", async () => {
    const body = { decision: "REJECT" as const, reason: "wrong party", lockVersion: 8 };
    const inv = await runMutation(useReviewAgreement, { id: "a1", versionNumber: 2, body });
    expect(lastUrl()).toBe("/api/t/acme/agreements/a1/versions/2/review");
    expect(lastInit().method).toBe("POST");
    expect(lastJson()).toEqual(body);
    expect(inv).toEqual(keys(...STANDARD));
  });

  it("useRecordSignature posts a multipart with a JSON signature part and an optional file, and also invalidates the case", async () => {
    const signature = { signatoryId: "s1", signedOn: "2026-10-01", method: "WET_INK", lockVersion: 9 };
    const file = new File(["x"], "signed.pdf", { type: "application/pdf" });
    const inv = await runMutation(useRecordSignature, { id: "a1", signature, file });
    expect(lastUrl()).toBe("/api/t/acme/agreements/a1/signatures");
    expect(lastInit().method).toBe("POST");
    const body = lastInit().body as FormData;
    const part = body.get("signature") as Blob;
    expect(part.type).toBe("application/json");
    expect(JSON.parse(await part.text())).toEqual(signature);
    expect((body.get("file") as File).name).toBe("signed.pdf");
    expect(inv).toEqual(keys(...STANDARD, caseKeys.detail("c1"), caseKeys.roadmap("c1")));
  });

  it("useRecordSignature omits the file part when none is given", async () => {
    const signature = { signatoryId: "s1", signedOn: "2026-10-01", method: "WET_INK" };
    await runMutation(useRecordSignature, { id: "a1", signature });
    const body = lastInit().body as FormData;
    expect(body.has("file")).toBe(false);
    expect(body.has("signature")).toBe(true);
  });

  it("useCancelAgreement posts the reason and invalidates BOTH the cancelled agreement's detail and the successor's", async () => {
    // The response is the SUCCESSOR draft (a2), not the cancelled agreement (a1).
    const inv = await runMutation(
      useCancelAgreement,
      { id: "a1", reason: "wrong terms", lockVersion: 10 },
      detail("a2", "c1"),
    );
    expect(lastUrl()).toBe("/api/t/acme/agreements/a1/cancel");
    expect(lastInit().method).toBe("POST");
    expect(lastJson()).toEqual({ reason: "wrong terms", lockVersion: 10 });
    expect(inv).toContain(JSON.stringify(agreementKeys.detail("a1")));
    expect(inv).toContain(JSON.stringify(agreementKeys.detail("a2")));
    expect(inv).toContain(JSON.stringify(agreementKeys.forCase("c1")));
    expect(inv).toContain(JSON.stringify(agreementKeys.index()));
    expect(inv).toContain(JSON.stringify(agreementKeys.summary()));
  });
});
