"use client";

import { useEffect, useState } from "react";

/**
 * Holds a value still until typing stops.
 *
 * Without it every keystroke is a request and a distinct cache entry, and the
 * results arrive out of order — the list ends up showing whichever response was
 * slowest rather than whatever was typed last.
 *
 * Lifted out of the customer list in Task 28, when the user list needed the same
 * thing. One copy, because two copies drift.
 */
export function useDebounced<T>(value: T, delay: number): T {
  const [settled, setSettled] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);

  return settled;
}
