"use client";

import { useState } from "react";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiError } from "./client";

/**
 * The TanStack Query client for the authenticated application.
 *
 * Not in Task 27's file list, and added deliberately: the dependency was
 * installed in Task 23 but nothing ever mounted a provider, so the first hook to
 * call useQuery would have thrown. It wraps the authenticated tree rather than
 * the root layout — the public auth pages fetch nothing through it, and a cache
 * that outlives sign-out would hand the next user the previous user's records
 * from memory.
 *
 * Created in state, not at module scope: a module-level client is shared across
 * every request in a server render, which on a multi-tenant product means one
 * tenant's data can be handed to another.
 */
export function QueryProvider({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            /**
             * A 4xx is an answer, not a failure to reach the server. Retrying a
             * 404 three times turns "this record is not visible to you" into
             * three seconds of spinner, and retrying a 403 hammers an endpoint
             * that has already refused. Only 5xx and network errors are retried.
             */
            retry: (failureCount, error) => {
              if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
                return false;
              }
              return failureCount < 2;
            },
            staleTime: 30_000,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );

  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
