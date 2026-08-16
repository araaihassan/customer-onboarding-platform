/**
 * Starts the Spring Boot backend for the end-to-end suite, and tees its output to
 * a log file.
 *
 * The log file is the point. Activation and reset tokens are `SecureRandom`
 * secrets that travel by email everywhere in this system — provisioning does not
 * return the administrator's token in its response, and the invitation endpoint
 * answers 204 with no body precisely so a caller never holds a credential for
 * someone else's account. Under the `dev` profile `LoggingEmailSender` logs each
 * message body in full, and that is the only place a test can read one.
 *
 * `playwright.config.ts` runs this as a `webServer` command rather than calling
 * gradlew directly, because Playwright pipes a webServer's stdout to the reporter
 * and offers no way to read it back from a test.
 *
 * Every setting is an override of the defaults CLAUDE.md documents, so a checkout
 * whose database is on 5432 needs no configuration at all.
 */
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { createWriteStream, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const frontend = join(here, "..", "..");
const backend = join(frontend, "..", "backend");

export const LOG_PATH = join(here, "..", ".artifacts", "backend.log");

/** Truncated, never appended: a stale token from a previous run reads as current. */
mkdirSync(dirname(LOG_PATH), { recursive: true });
const log = createWriteStream(LOG_PATH, { flags: "w" });

/**
 * On Windows the wrapper is a .bat, which Node cannot exec directly — but
 * `shell: true` there concatenates rather than escapes the arguments and Node 22
 * emits a DeprecationWarning for it. Naming the interpreter explicitly gets the
 * same result with the arguments passed as a real argv.
 */
const [command, leadingArgs] =
  process.platform === "win32"
    ? [process.env.ComSpec ?? "cmd.exe", ["/d", "/s", "/c", join(backend, "gradlew.bat")]]
    : [join(backend, "gradlew"), []];

const child = spawn(command, [...leadingArgs, "bootRun", "--console=plain"], {
  cwd: backend,
  env: {
    ...process.env,
    // dev, so LoggingEmailSender is the active EmailSender. It must never be in
    // production, which is what its @Profile enforces.
    SPRING_PROFILES_ACTIVE: "dev",
    DB_URL: process.env.DB_URL ?? "jdbc:postgresql://localhost:5432/onboarding",
    DB_OWNER_USER: process.env.DB_OWNER_USER ?? "postgres",
    DB_OWNER_PASSWORD: process.env.DB_OWNER_PASSWORD ?? "postgres",
    DB_APP_USER: process.env.DB_APP_USER ?? "onboarding_app",
    DB_APP_PASSWORD: process.env.DB_APP_PASSWORD ?? "onboarding_app",
    // Without a platform administrator, /api/platform/tenants cannot be called
    // and the suite has no tenant to run against.
    APP_PLATFORM_ADMIN_EMAIL: process.env.APP_PLATFORM_ADMIN_EMAIL ?? "ops@example.com",
    APP_PLATFORM_ADMIN_PASSWORD:
      process.env.APP_PLATFORM_ADMIN_PASSWORD ?? "e2e-platform-admin-password",
    // Generated per run, not written down. application.yml ships no fallback and
    // JwtProperties refuses to start without a usable secret, so this harness has
    // to supply one — but a literal here is a secret published in the repository,
    // and this project denylists those. The suite never needs it to be stable: one
    // backend process serves the whole run, and every token it mints dies with it.
    JWT_SECRET: process.env.JWT_SECRET ?? randomBytes(48).toString("base64url"),
  },
});

for (const stream of [child.stdout, child.stderr]) {
  stream.pipe(log);
  stream.pipe(process.stdout);
}

child.on("exit", (code) => process.exit(code ?? 0));

// Playwright kills this process to stop the server; forward it so no gradle
// daemon is left holding :8080 for the next run.
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    child.kill(signal);
  });
}
