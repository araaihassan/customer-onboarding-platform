"use client";

import { useContext } from "react";
import { AuthContext } from "./AuthProvider";
import type { AuthState } from "./types";

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) {
    // Throwing beats returning a signed-out default: a component rendered outside
    // the provider would otherwise silently believe nobody is logged in, and hide
    // every affordance for no discoverable reason.
    throw new Error("useAuth must be used inside an AuthProvider");
  }
  return context;
}
