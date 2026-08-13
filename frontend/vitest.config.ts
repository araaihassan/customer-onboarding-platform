import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    // Vitest does not read tsconfig "paths", so the @/ alias has to be declared
    // again here. Without it every test importing @/… fails to resolve, and the
    // failure looks like a missing file rather than a missing alias.
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    // jsdom because component tests arrive with the shell in Task 26; the pure
    // modules tested so far need none of it.
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
