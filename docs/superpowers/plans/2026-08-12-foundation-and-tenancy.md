# Foundation & Tenancy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the multi-tenant foundation — tenancy with database-enforced isolation, identity, RBAC with record-level scope, authentication, audit, and customer management — that sub-projects 2–10 are built on.

**Architecture:** A Spring Boot modular monolith organized by domain (`tenancy`, `identity`, `authz`, `audit`, `customer`) behind a Next.js App Router frontend. Tenant isolation is enforced twice: a Hibernate filter in the application and PostgreSQL Row-Level Security in the database. Authorization is centralized — a permission gate on service methods plus a query-predicate builder that endpoints cannot bypass.

**Tech Stack:** Java 21, Spring Boot 3.4, Gradle (Kotlin DSL), PostgreSQL 16, Flyway, Hibernate/JPA, Argon2id, JJWT, JUnit 5, Testcontainers, ArchUnit, Next.js 15 (App Router), TypeScript strict, Tailwind, shadcn/ui, TanStack Query, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-12-foundation-and-tenancy-design.md`

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Java 21**, **Spring Boot 3.4.x**, **PostgreSQL 16**. No Redis. No Kubernetes.
- **Base package:** `co.ara.onboarding`. Domain modules are subpackages: `co.ara.onboarding.tenancy`, `.identity`, `.authz`, `.audit`, `.customer`, `.platform`.
- **Two PostgreSQL roles.** Migrations run as the schema owner. The application connects as `onboarding_app`, a **non-superuser, non-BYPASSRLS** role. This is mandatory: RLS does not constrain superusers or (without `FORCE`) table owners, so connecting as the owner would make every isolation test pass vacuously.
- **Every tenant-owned table** has a non-null `tenant_id`, an RLS policy, and `ALTER TABLE ... FORCE ROW LEVEL SECURITY`, all created in the same migration as the table.
- **Migrations are forward-only.** Never edit a migration that has been committed.
- **No hard deletes** of business records. Deactivate instead (spec §9.4).
- **DELETE is deny-by-default at the database layer.** `ALTER DEFAULT PRIVILEGES` grants `SELECT, INSERT, UPDATE` only; `V2_1__revoke_default_delete.sql` strips `DELETE` from the default grant and from `tenant`. Any table that genuinely needs deletion must carry an explicit `GRANT DELETE ON <table> TO onboarding_app;` in its own migration, with a comment saying why. In sub-project 1 that is only: `role`, `role_grant`, `user_role`, `team_member`, and `login_attempt` — all authorization or session bookkeeping, never business records. Grant-then-revoke is the wrong shape here: a table nobody remembered to revoke stays deletable, which is precisely backwards from how RLS, scope predicates, and the permission model all fail closed.
- **No deny grants** in authorization. Absence of a grant is the denial (spec §6.5).
- **Permissions are never embedded in tokens.** Authority is resolved server-side per request (spec §6.7, §7.2).
- **Out-of-scope records return 404, never 403** (spec §6.8).
- **TDD.** Every task writes a failing test first. Security tests are written before the mechanism they verify.
- **Never assert an exception *inside* a `fixture.runAs(...)` / `runAsUser(...)` lambda.** Those helpers run the action in a `TransactionTemplate`. Catching an exception inside the lambda — which is what `assertThatThrownBy` does — leaves the transaction marked rollback-only while execution continues to normal completion, so the template then attempts a commit and throws `UnexpectedRollbackException`, masking the exception you meant to assert on. Wrap the helper instead:

  ```java
  // WRONG — UnexpectedRollbackException masks the real failure
  fixture.runAs(tenant, () ->
      assertThatThrownBy(() -> roles.createRole(...)).isInstanceOf(InvalidGrantException.class));

  // RIGHT — the exception propagates out, the template rolls back and rethrows it
  assertThatThrownBy(() -> fixture.runAs(tenant, () -> roles.createRole(...)))
      .isInstanceOf(InvalidGrantException.class);
  ```

  Several code samples later in this plan still show the wrong form; correct them as you encounter them rather than copying them verbatim. This applies to any exception that marks the transaction rollback-only, which includes every `RuntimeException` crossing a `@Transactional` boundary — `DataIntegrityViolationException`, `AccessDeniedException`, `InvalidGrantException`, `NoSuchElementException`, and `IllegalStateException` among them.
- **Commit at the end of every task.** Conventional Commits (`feat:`, `test:`, `chore:`, `fix:`).
- **Test commands** are written as `./gradlew` (Git Bash). On PowerShell use `.\gradlew.bat` with the same arguments.
- **UUIDv7 primary keys** via `UUID` columns, generated in application code by `co.ara.onboarding.platform.Uuid7.generate()` (implemented in Task 2). Time-ordered keys keep B-tree inserts local, which matters most for `audit_event`, the highest-volume append-only table.
  **Every `UUID.randomUUID()` appearing in this plan's code samples means `Uuid7.generate()`** — the samples were written before the utility was named. The sole exceptions are values that must be unpredictable rather than merely unique; those use `SecureRandom` directly and never a UUID (refresh tokens, invitation tokens).
- **All timestamps** are `timestamptz`, stored in UTC.

---

## File Structure

### Backend — `backend/`

| Path | Responsibility |
|---|---|
| `build.gradle.kts`, `settings.gradle.kts` | Build configuration |
| `src/main/resources/application.yml` | Datasource, Flyway, JWT config |
| `src/main/resources/db/migration/V*__*.sql` | Flyway migrations, one per feature |
| `platform/BaseEntity.java` | Shared id/timestamps |
| `platform/RequestAuditContext.java` | Request-scoped actor/IP/user-agent holder |
| `tenancy/Tenant.java`, `TenantRepository.java` | Tenant record |
| `tenancy/TenantContext.java` | Thread-local current tenant |
| `tenancy/TenantResolver.java`, `PathPrefixTenantResolver.java` | `/t/{slug}` resolution |
| `tenancy/TenantContextFilter.java` | Sets `TenantContext` + `app.tenant_id` per request |
| `tenancy/TenantScopedEntity.java` | MappedSuperclass carrying `tenant_id` + Hibernate filter |
| `tenancy/TenantProvisioningService.java` | Creates tenants, seeds role templates |
| `identity/AppUser.java`, `Department.java`, `Team.java`, `PlatformAdmin.java` | Identity entities |
| `identity/*Repository.java` | Identity persistence |
| `authz/PermissionCatalog.java`, `PermissionKeys.java`, `Scope.java`, `RelationshipType.java` | Code-defined catalog |
| `authz/PermissionSyncRunner.java` | Startup sync of catalog → `permission` table |
| `authz/Role.java`, `RoleGrant.java`, `RoleRepository.java` | Tenant-owned roles |
| `authz/RoleTemplates.java` | The 12 seeded role templates |
| `authz/AuthContext.java`, `AuthContextProvider.java` | Current user's tenant/department/teams |
| `authz/EffectivePermissions.java`, `AuthorizationService.java` | Per-request permission resolution |
| `authz/ResourceAuthorizationDescriptor.java`, `DescriptorRegistry.java` | Per-resource scope predicates |
| `authz/AuthorizationPredicateBuilder.java` | Scope → JPA `Specification` |
| `authz/RequirePermission.java`, `PermissionGateAspect.java` | Service-method gate |
| `authz/AuthorizedRepository.java` | Repository base that requires a permission key |
| `audit/AuditAction.java`, `AuditActions.java`, `AuditEvent.java`, `AuditRecorder.java` | Audit substrate |
| `auth/TokenService.java`, `RefreshTokenService.java`, `LoginThrottleService.java` | Session machinery |
| `auth/AuthController.java`, `MeController.java` | Login, refresh, logout, `/me` |
| `auth/EmailSender.java`, `SmtpEmailSender.java`, `LoggingEmailSender.java` | Outbound email |
| `auth/InvitationService.java`, `PasswordResetService.java` | Token flows |
| `customer/Customer.java`, `CustomerContact.java` | Customer domain |
| `customer/CustomerDescriptor.java` | Scope predicates for `customer` |
| `customer/CustomerService.java`, `CustomerController.java` | Customer operations |
| `src/test/.../support/PostgresTestBase.java` | Testcontainers harness |
| `src/test/.../architecture/` | ArchUnit + RLS meta-tests |
| `src/test/.../security/` | The eight negative security tests |

### Frontend — `frontend/`

| Path | Responsibility |
|---|---|
| `src/lib/api/client.ts` | Fetch wrapper: bearer token, 401 refresh-and-retry |
| `src/lib/api/generated.ts` | Types generated from OpenAPI |
| `src/lib/auth/AuthProvider.tsx` | In-memory access token, session state |
| `src/lib/auth/useHasPermission.ts` | Permission-aware UI (convenience only) |
| `src/lib/i18n/` | Translation layer, English only |
| `src/app/(public)/` | Login, activation, password reset |
| `src/app/(app)/` | Authenticated shell + pages |
| `src/components/shell/` | Sidebar, top bar, theme toggle |
| `e2e/` | Playwright specs |

---

## Task Sequence

**Phase A — Tenancy, identity & audit foundation:** Tasks 1–7
**Phase B — Authorization:** Tasks 8–14
**Phase C — Authentication:** Tasks 15–19
**Phase D — Domain surface & the security gate:** Tasks 20–22
**Phase E — Frontend:** Tasks 23–28
**Phase F — Handoff:** Task 29

---

### Task 1: Backend scaffold and Testcontainers harness

Establishes the build and a test base that talks to real PostgreSQL. Every later task depends on this harness.

**Files:**
- Create: `backend/settings.gradle.kts`
- Create: `backend/build.gradle.kts`
- Create: `backend/src/main/java/co/ara/onboarding/OnboardingApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/co/ara/onboarding/support/PostgresTestBase.java`
- Test: `backend/src/test/java/co/ara/onboarding/ApplicationContextTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `PostgresTestBase` — abstract class every integration test extends. Starts a `postgres:16-alpine` container shared across the suite.

- [ ] **Step 1: Initialize the Gradle project**

```bash
mkdir -p backend/src/main/java/co/ara/onboarding backend/src/main/resources backend/src/test/java/co/ara/onboarding
cd backend && gradle init --type basic --dsl kotlin --project-name onboarding-backend --no-split-project --incubating
```

If `gradle` is not installed, download the wrapper from an existing project or run `gradle wrapper --gradle-version 8.10`.

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "onboarding-backend"
```

- [ ] **Step 3: Write `build.gradle.kts`**

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "co.ara"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.7.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.withType<Test> { useJUnitPlatform() }
```

- [ ] **Step 4: Write the application class**

`backend/src/main/java/co/ara/onboarding/OnboardingApplication.java`:

```java
package co.ara.onboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OnboardingApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnboardingApplication.class, args);
    }
}
```

- [ ] **Step 5: Write `application.yml`**

Note the two roles: Flyway connects as the owner, the application datasource as `onboarding_app`. The `onboarding_app` role is created by the first migration (Task 2), so on a brand-new database Flyway must run before the app datasource is used — which is Spring Boot's default ordering.

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/onboarding}
    username: ${DB_APP_USER:onboarding_app}
    password: ${DB_APP_PASSWORD:onboarding_app}
  flyway:
    enabled: true
    url: ${DB_URL:jdbc:postgresql://localhost:5432/onboarding}
    user: ${DB_OWNER_USER:postgres}
    password: ${DB_OWNER_PASSWORD:postgres}
    locations: classpath:db/migration
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: UTC

app:
  jwt:
    issuer: onboarding-platform
    access-token-ttl: PT15M
    secret: ${JWT_SECRET:dev-only-secret-replace-in-production-min-32-bytes}
  refresh-token:
    ttl: P14D
  tenant:
    resolution: path-prefix
```

- [ ] **Step 6: Write the failing test**

`backend/src/test/java/co/ara/onboarding/support/PostgresTestBase.java`:

```java
package co.ara.onboarding.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public abstract class PostgresTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static { POSTGRES.start(); }

    /**
     * Flyway runs as the container's superuser (the schema owner).
     * The application datasource is repointed to onboarding_app in Task 2,
     * once the migration that creates that role exists.
     */
    @DynamicPropertySource
    static void flywayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
```

`backend/src/test/java/co/ara/onboarding/ApplicationContextTest.java`:

```java
package co.ara.onboarding;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.assertj.core.api.Assertions.assertThat;

class ApplicationContextTest extends PostgresTestBase {

    @Autowired DataSource dataSource;

    @Test
    void connectsToRealPostgres() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
    }
}
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.ApplicationContextTest"`
Expected: FAIL — no migrations exist yet, or context startup fails. Confirm the failure is about the application, not about Docker being unavailable. If Docker is not running, start it before continuing.

- [ ] **Step 8: Add an empty baseline migration so Flyway succeeds**

`backend/src/main/resources/db/migration/V1__baseline.sql`:

```sql
-- Baseline. Schema objects begin in V2.
SELECT 1;
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.ApplicationContextTest"`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add backend/
git commit -m "chore: scaffold Spring Boot backend with Testcontainers harness"
```

---

### Task 2: Application database role and tenant table

Creates the non-superuser role that RLS will actually constrain, plus the `tenant` table. The role split is the single most important detail in this task — without it every isolation test in this plan is meaningless.

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__app_role_and_tenant.sql`
- Create: `backend/src/main/java/co/ara/onboarding/platform/Uuid7.java`
- Create: `backend/src/test/java/co/ara/onboarding/platform/Uuid7Test.java`
- Create: `backend/src/main/java/co/ara/onboarding/platform/BaseEntity.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/Tenant.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/TenantRepository.java`
- Modify: `backend/src/test/java/co/ara/onboarding/support/PostgresTestBase.java`
- Test: `backend/src/test/java/co/ara/onboarding/tenancy/TenantRepositoryTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/support/AppRoleTest.java`

**Interfaces:**
- Consumes: `PostgresTestBase` (Task 1).
- Produces:
  - `BaseEntity` — `@MappedSuperclass` with `UUID id`, `Instant createdAt`, `Instant updatedAt`.
  - `Tenant` — fields `id`, `slug`, `name`, `status` (`TenantStatus` enum: `ACTIVE`, `SUSPENDED`), `settings` (String JSON), timestamps.
  - `TenantRepository extends JpaRepository<Tenant, UUID>` with `Optional<Tenant> findBySlug(String slug)`.
  - Database role `onboarding_app` with `NOLOGIN`-free login access, no superuser, no `BYPASSRLS`.

- [ ] **Step 1: Write the failing test for the application role**

`backend/src/test/java/co/ara/onboarding/support/AppRoleTest.java`:

```java
package co.ara.onboarding.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class AppRoleTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void applicationConnectsAsNonSuperuserWithoutBypassRls() {
        String currentUser = jdbc.queryForObject("SELECT current_user", String.class);
        assertThat(currentUser).isEqualTo("onboarding_app");

        Boolean superuser = jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class);
        Boolean bypassRls = jdbc.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);

        assertThat(superuser).as("app role must not be superuser").isFalse();
        assertThat(bypassRls).as("app role must not have BYPASSRLS").isFalse();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.support.AppRoleTest"`
Expected: FAIL — `current_user` is the container superuser, not `onboarding_app`.

- [ ] **Step 3: Write the migration**

`backend/src/main/resources/db/migration/V2__app_role_and_tenant.sql`:

```sql
-- Application role. Deliberately NOT superuser and NOT BYPASSRLS:
-- RLS does not constrain either, so the app must connect as a plain role.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'onboarding_app') THEN
        CREATE ROLE onboarding_app LOGIN PASSWORD 'onboarding_app';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO onboarding_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO onboarding_app;

CREATE TABLE tenant (
    id          uuid PRIMARY KEY,
    slug        varchar(63)  NOT NULL UNIQUE,
    name        varchar(255) NOT NULL,
    status      varchar(32)  NOT NULL,
    settings    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

-- tenant is intentionally NOT tenant-scoped: it is the tenant registry itself.
-- It appears on the RLS meta-test allowlist in Task 7.
GRANT SELECT, INSERT, UPDATE ON tenant TO onboarding_app;
```

- [ ] **Step 4: Repoint the test datasource at the application role**

Add to `PostgresTestBase`:

```java
    @DynamicPropertySource
    static void appDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "onboarding_app");
        registry.add("spring.datasource.password", () -> "onboarding_app");
    }
```

- [ ] **Step 5: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.support.AppRoleTest"`
Expected: PASS

- [ ] **Step 5b: Write the failing test for UUIDv7 generation**

`backend/src/test/java/co/ara/onboarding/platform/Uuid7Test.java`:

```java
package co.ara.onboarding.platform;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class Uuid7Test {

    @Test
    void hasVersionSevenAndRfc4122Variant() {
        UUID id = Uuid7.generate();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void idsGeneratedInSequenceSortAscending() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5000; i++) ids.add(Uuid7.generate());

        List<UUID> sorted = new ArrayList<>(ids);
        sorted.sort(Uuid7::compareUnsigned);
        assertThat(sorted)
            .as("time-ordered keys must be monotonic even within the same millisecond")
            .isEqualTo(ids);
    }

    @Test
    void encodesCurrentTimeInTheLeading48Bits() {
        long before = System.currentTimeMillis();
        UUID id = Uuid7.generate();
        long after = System.currentTimeMillis();

        long timestamp = id.getMostSignificantBits() >>> 16;
        assertThat(timestamp).isBetween(before, after);
    }

    @Test
    void generatesDistinctValuesUnderContention() throws Exception {
        var ids = java.util.Collections.synchronizedSet(new java.util.HashSet<UUID>());
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 2000; i++) ids.add(Uuid7.generate());
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
        assertThat(ids).hasSize(8 * 2000);
    }
}
```

- [ ] **Step 5c: Implement `Uuid7`**

```java
package co.ara.onboarding.platform;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RFC 9562 version 7 UUIDs: a 48-bit millisecond timestamp followed by
 * randomness, so primary keys sort by creation time and index inserts stay
 * local instead of scattering across the B-tree.
 *
 * These IDs deliberately leak creation time. That is acceptable for entity
 * identifiers; anything that must be unpredictable (refresh tokens,
 * invitation tokens) uses SecureRandom bytes directly and never a UUID.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Packs the last timestamp and counter into one long for lock-free CAS. */
    private static final AtomicLong LAST = new AtomicLong();

    private static final int COUNTER_BITS = 12;
    private static final long COUNTER_MASK = (1L << COUNTER_BITS) - 1;

    private Uuid7() {}

    public static UUID generate() {
        long stamp = nextStamp();
        long millis = stamp >>> COUNTER_BITS;
        long counter = stamp & COUNTER_MASK;

        // 48 bits timestamp | 4 bits version (7) | 12 bits monotonic counter
        long msb = (millis << 16) | (0x7L << 12) | counter;

        // 2 bits variant (RFC 4122) | 62 bits randomness
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

        return new UUID(msb, lsb);
    }

    /**
     * Returns a strictly increasing (millis, counter) pair. Within a single
     * millisecond the counter advances; if it saturates, the timestamp is
     * borrowed forward so ordering is never violated.
     */
    private static long nextStamp() {
        while (true) {
            long previous = LAST.get();
            long now = System.currentTimeMillis();
            long candidate = (now << COUNTER_BITS);
            long next = (candidate > previous) ? candidate : previous + 1;
            if (LAST.compareAndSet(previous, next)) return next;
        }
    }

    /** UUIDs compare signed by default, which breaks ordering above 0x7F. */
    public static int compareUnsigned(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high
                : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
```

`java.util.UUID.compareTo` compares signed, so it orders v7 IDs incorrectly once the high bit is set. Sort with `Uuid7::compareUnsigned` in application code; PostgreSQL's `uuid` type already compares unsigned, so index ordering is correct there regardless.

- [ ] **Step 6: Write the failing test for the tenant entity**

`backend/src/test/java/co/ara/onboarding/tenancy/TenantRepositoryTest.java`:

```java
package co.ara.onboarding.tenancy;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class TenantRepositoryTest extends PostgresTestBase {

    @Autowired TenantRepository tenants;

    @Test
    void persistsAndLooksUpBySlug() {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setSlug("acme");
        t.setName("Acme Corp");
        t.setStatus(TenantStatus.ACTIVE);
        tenants.save(t);

        assertThat(tenants.findBySlug("acme")).isPresent()
                .get().extracting(Tenant::getName).isEqualTo("Acme Corp");
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.tenancy.TenantRepositoryTest"`
Expected: FAIL — `Tenant`, `TenantStatus`, and `TenantRepository` do not exist.

- [ ] **Step 8: Implement `BaseEntity`, `Tenant`, `TenantStatus`, `TenantRepository`**

`platform/BaseEntity.java`:

```java
package co.ara.onboarding.platform;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
public abstract class BaseEntity {
    @Id private UUID id;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

`tenancy/TenantStatus.java`:

```java
package co.ara.onboarding.tenancy;

public enum TenantStatus { ACTIVE, SUSPENDED }
```

`tenancy/Tenant.java`:

```java
package co.ara.onboarding.tenancy;

import co.ara.onboarding.platform.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "tenant")
public class Tenant extends BaseEntity {

    @Column(nullable = false, unique = true) private String slug;
    @Column(nullable = false) private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private TenantStatus status;

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public TenantStatus getStatus() { return status; }
    public void setStatus(TenantStatus status) { this.status = status; }
}
```

`tenancy/TenantRepository.java`:

```java
package co.ara.onboarding.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlug(String slug);
}
```

- [ ] **Step 9: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.tenancy.*"`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add backend/
git commit -m "feat: add onboarding_app database role and tenant registry"
```

- [ ] **Step 11: Make DELETE deny-by-default**

`ALTER DEFAULT PRIVILEGES` in `V2` grants `DELETE` on every table created afterwards, which silently overrides the narrower per-table grants and leaves `onboarding_app` able to `DELETE FROM tenant`. Since `V2` is already committed and migrations are forward-only, correct it with a new migration rather than an edit.

`backend/src/main/resources/db/migration/V2_1__revoke_default_delete.sql`:

```sql
-- DELETE is deny-by-default. V2's ALTER DEFAULT PRIVILEGES granted it on all
-- future tables, which defeats per-table grants and contradicts the project's
-- no-hard-deletes rule. Tables that genuinely need DELETE grant it explicitly
-- in their own migration, with a comment saying why.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE DELETE ON TABLES FROM onboarding_app;

REVOKE DELETE ON tenant FROM onboarding_app;
```

Flyway orders `V2` < `V2.1` < `V3`, so this slots in without renumbering any later migration.

- [ ] **Step 12: Write the test that proves it**

Add to `backend/src/test/java/co/ara/onboarding/support/AppRoleTest.java`:

```java
    @Test
    void applicationRoleCannotDeleteFromTenantRegistry() {
        assertThatThrownBy(() -> jdbc.execute("DELETE FROM tenant"))
                .hasStackTraceContaining("permission denied for table tenant");
    }

    @Test
    void deleteIsNotGrantedByDefaultOnNewTables() {
        // Proves the default-privilege change, not just the one-off tenant revoke:
        // a table created after V2_1 must not inherit DELETE.
        // Named rls_probe_* so Task 7's RlsCoverageTest, which is deny-by-default
        // over every table in the schema, skips it. Do not rename.
        // DDL as owner — onboarding_app has no CREATE on the schema — but the
        // DELETE under test must run as onboarding_app or it proves nothing.
        ownerJdbc().execute("CREATE TABLE IF NOT EXISTS rls_probe_delete (id uuid PRIMARY KEY)");
        ownerJdbc().execute("GRANT SELECT, INSERT ON rls_probe_delete TO onboarding_app");
        assertThatThrownBy(() -> jdbc.execute("DELETE FROM rls_probe_delete"))
                .hasStackTraceContaining("permission denied for table rls_probe_delete");
    }
```

The second test is the one that matters. Revoking on `tenant` alone would pass a test that only checks `tenant`, while every future table silently kept the inherited grant.

Two mechanics that are easy to get wrong here, both discovered the hard way:

- **`hasStackTraceContaining`, not `hasMessageContaining`.** `JdbcTemplate` wraps the driver's error in a `BadSqlGrammarException` whose own message is generic; the Postgres text `permission denied for table …` lives on the cause. A `hasMessageContaining("permission denied")` assertion never matches, so the test fails even when the migration is correct — and the tempting conclusion is that the migration is broken.
- **DDL as owner, assertion as `onboarding_app`.** `onboarding_app` has no `CREATE` on the schema, so creating the probe through the autowired template fails outright. Creating *and* deleting as the owner would "pass" while testing nothing, since the owner bypasses the privilege being checked.

`ownerJdbc()` is added to `PostgresTestBase` in Task 3 Step 0; if you are doing Task 2 before that exists, add the helper here and Task 3 will simply use it.

- [ ] **Step 13: Run and commit**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.support.AppRoleTest"`
Expected: PASS — three tests.

```bash
git add backend/
git commit -m "fix: make DELETE deny-by-default for the application role"
```

---

### Task 3: Tenant context and RLS infrastructure

Introduces the thread-local tenant, the connection-level `app.tenant_id` setting that RLS policies read, and a reusable migration helper so every future table gets its policy the same way.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/TenantContext.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/TenantScopedEntity.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/TenantConnectionCustomizer.java`
- Create: `backend/src/main/resources/db/migration/V3__rls_helper.sql`
- Test: `backend/src/test/java/co/ara/onboarding/tenancy/RlsIsolationTest.java`

**Interfaces:**
- Consumes: `Tenant`, `PostgresTestBase`.
- Produces:
  - `TenantContext.set(UUID)`, `TenantContext.getRequired()` (throws `IllegalStateException` when unset), `TenantContext.clear()`, `TenantContext.runAs(UUID, Runnable)`.
  - `TenantScopedEntity extends BaseEntity` — adds `@Column(name="tenant_id") UUID tenantId` with getter/setter, and declares the Hibernate `@FilterDef`/`@Filter` named `tenantFilter`.
  - SQL function `enable_tenant_rls(text)` — applied to every tenant-owned table in later migrations.

- [ ] **Step 0: Add an owner-role JDBC helper to `PostgresTestBase`**

Tests in this task and in Tasks 6 and 7 need to run DDL and `GRANT` statements, which `onboarding_app` deliberately cannot do — it has no `CREATE` on the schema. Running those statements through the ordinary autowired `JdbcTemplate` fails with `permission denied for schema public`. Worse, if a test ran its *assertions* as the owner too, it would be testing ownership bypass rather than the privilege or policy under test.

So expose a second template bound to the migration-owner role, and keep the distinction explicit: **DDL and grants as owner; the behaviour under test as `onboarding_app`.**

Add to `PostgresTestBase`:

```java
    /**
     * JdbcTemplate bound to the container's owner role, for DDL and GRANT only.
     * Never assert privilege or RLS behaviour through this — the owner bypasses
     * both, so an assertion made here proves nothing.
     */
    protected static JdbcTemplate ownerJdbc() {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(ds);
    }
```

- [ ] **Step 1: Write the failing isolation test**

Note the split: `ownerJdbc()` creates and grants; the autowired `jdbc` (which is `onboarding_app`) does the inserts and selects whose visibility is the actual subject of the test.

`backend/src/test/java/co/ara/onboarding/tenancy/RlsIsolationTest.java`:

```java
package co.ara.onboarding.tenancy;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RlsIsolationTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void rlsHidesRowsOfOtherTenants() {
        // DDL and grants as owner; everything asserted below runs as onboarding_app.
        ownerJdbc().execute("""
            CREATE TABLE IF NOT EXISTS rls_probe (
                id uuid PRIMARY KEY, tenant_id uuid NOT NULL, label text NOT NULL)
            """);
        ownerJdbc().execute("SELECT enable_tenant_rls('rls_probe')");
        ownerJdbc().execute("GRANT SELECT, INSERT ON rls_probe TO onboarding_app");

        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        jdbc.execute("SET app.tenant_id = '" + tenantA + "'");
        jdbc.update("INSERT INTO rls_probe VALUES (?, ?, 'a-row')", UUID.randomUUID(), tenantA);

        jdbc.execute("SET app.tenant_id = '" + tenantB + "'");
        jdbc.update("INSERT INTO rls_probe VALUES (?, ?, 'b-row')", UUID.randomUUID(), tenantB);

        Integer visibleToB = jdbc.queryForObject("SELECT count(*) FROM rls_probe", Integer.class);
        assertThat(visibleToB).as("tenant B must see only its own row").isEqualTo(1);

        jdbc.execute("SET app.tenant_id = '" + tenantA + "'");
        String label = jdbc.queryForObject("SELECT label FROM rls_probe", String.class);
        assertThat(label).isEqualTo("a-row");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.tenancy.RlsIsolationTest"`
Expected: FAIL — function `enable_tenant_rls` does not exist.

- [ ] **Step 3: Write the RLS helper migration**

`backend/src/main/resources/db/migration/V3__rls_helper.sql`:

```sql
-- Applies the standard tenant isolation policy to a table.
-- FORCE is required so the policy also applies to the table owner.
CREATE OR REPLACE FUNCTION enable_tenant_rls(target_table text)
RETURNS void AS $$
BEGIN
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', target_table);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', target_table);
    -- current_setting('app.tenant_id', true) returns NULL when the GUC was
    -- never set in this session, but PostgreSQL resets a custom (placeholder)
    -- GUC to '' -- not NULL -- on RESET, and ''::uuid raises a cast error
    -- rather than failing closed. nullif(...,'') collapses both "never set"
    -- and "reset" to NULL so tenant_id = NULL (never true) is what actually
    -- runs in both cases -- fail-closed without an exception.
    EXECUTE format($f$
        CREATE POLICY tenant_isolation ON %I
        USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    $f$, target_table);
END;
$$ LANGUAGE plpgsql;
```

The `nullif` is not decoration. `current_setting(name, true)` returns NULL only when the setting was *never* established in the session; once `SET app.tenant_id` has run, a subsequent `RESET` leaves it as the empty string. `''::uuid` then raises `invalid input syntax for type uuid`, so the policy would error rather than fail closed. An erroring policy still denies access, but it turns a clean "you see nothing" into an exception surfacing from arbitrary queries — and the obvious fix under time pressure is to loosen the predicate, which is how a fail-closed policy becomes a fail-open one. Collapsing both cases to NULL keeps `tenant_id = NULL` (never true) as the actual evaluated expression.

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.tenancy.RlsIsolationTest"`
Expected: PASS

- [ ] **Step 5: Write the failing test for unset-context fail-closed behaviour**

Add to `RlsIsolationTest`:

```java
    @Test
    void unsetTenantContextSeesNothing() {
        ownerJdbc().execute("""
            CREATE TABLE IF NOT EXISTS rls_probe2 (
                id uuid PRIMARY KEY, tenant_id uuid NOT NULL)
            """);
        ownerJdbc().execute("SELECT enable_tenant_rls('rls_probe2')");
        ownerJdbc().execute("GRANT SELECT, INSERT ON rls_probe2 TO onboarding_app");

        UUID tenant = UUID.randomUUID();
        jdbc.execute("SET app.tenant_id = '" + tenant + "'");
        jdbc.update("INSERT INTO rls_probe2 VALUES (?, ?)", UUID.randomUUID(), tenant);

        jdbc.execute("RESET app.tenant_id");
        Integer visible = jdbc.queryForObject("SELECT count(*) FROM rls_probe2", Integer.class);
        assertThat(visible).as("no tenant context must reveal no rows").isZero();
    }
```

- [ ] **Step 6: Run it — it should already pass**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.tenancy.RlsIsolationTest"`
Expected: PASS. This test documents an invariant rather than driving new code; if it fails, the policy in Step 3 is wrong.

- [ ] **Step 7: Implement `TenantContext`**

```java
package co.ara.onboarding.tenancy;

import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) { CURRENT.set(tenantId); }

    public static UUID getRequired() {
        UUID id = CURRENT.get();
        if (id == null) throw new IllegalStateException("No tenant context bound to this thread");
        return id;
    }

    public static UUID getOrNull() { return CURRENT.get(); }

    public static void clear() { CURRENT.remove(); }

    public static void runAs(UUID tenantId, Runnable action) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try { action.run(); } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
```

- [ ] **Step 8: Implement `TenantScopedEntity`**

```java
package co.ara.onboarding.tenancy;

import co.ara.onboarding.platform.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import java.util.UUID;

@MappedSuperclass
@FilterDef(name = "tenantFilter",
        parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class TenantScopedEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
}
```

- [ ] **Step 9: Implement `TenantConnectionCustomizer`**

Sets `app.tenant_id` on the JDBC connection for the current transaction, so RLS sees it.

```java
package co.ara.onboarding.tenancy;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Component
public class TenantConnectionCustomizer {

    private final EntityManager entityManager;

    public TenantConnectionCustomizer(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Binds the current tenant to both the DB session and the Hibernate filter.
     * MANDATORY is deliberate: set_config(..., true) is transaction-scoped, so
     * binding outside a transaction would silently discard the setting and leave
     * every RLS-protected query returning nothing. Fail loudly instead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
        session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
    }
}
```

`set_config(..., true)` scopes the setting to the transaction, so a pooled connection cannot leak a tenant into the next request.

- [ ] **Step 10: Commit**

```bash
git add backend/
git commit -m "feat: add tenant context and PostgreSQL RLS infrastructure"
```

---

### Task 4: Tenant resolution and the request pipeline

Resolves `/api/t/{slug}` into a bound tenant for the request, and guarantees the binding is cleared afterwards so a pooled thread cannot carry a tenant into the next request.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/TenantResolver.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/PathPrefixTenantResolver.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/TenantContextFilter.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/TenantTransactionBinder.java`
- Create: `backend/src/main/java/co/ara/onboarding/platform/TransactionConfig.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/UnknownTenantException.java`
- Create: `backend/src/main/java/co/ara/onboarding/platform/ApiExceptionHandler.java`
- Test: `backend/src/test/java/co/ara/onboarding/tenancy/TenantResolutionTest.java`

**Interfaces:**
- Consumes: `TenantContext`, `TenantRepository`, `TenantConnectionCustomizer`.
- Produces:
  - `TenantResolver` — `Optional<String> resolveSlug(HttpServletRequest request)`. Swapping to subdomains later means one new implementation of this interface (spec §4.3).
  - URL convention: tenant-scoped endpoints live under `/api/t/{tenantSlug}/**`; platform-admin endpoints under `/api/platform/**`.
  - `ApiExceptionHandler` — maps `UnknownTenantException` → 404, `AccessDeniedException` → 403, `NoSuchElementException` → 404.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/co/ara/onboarding/tenancy/TenantResolutionTest.java`:

```java
package co.ara.onboarding.tenancy;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = true)
class TenantResolutionTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired TenantRepository tenants;

    @BeforeEach
    void seedTenant() {
        if (tenants.findBySlug("acme").isEmpty()) {
            Tenant t = new Tenant();
            t.setId(UUID.randomUUID());
            t.setSlug("acme");
            t.setName("Acme Corp");
            t.setStatus(TenantStatus.ACTIVE);
            tenants.save(t);
        }
    }

    @Test
    void bindsTenantFromPathPrefix() throws Exception {
        mvc.perform(get("/api/t/acme/_debug/tenant"))
           .andExpect(status().isOk())
           .andExpect(content().string("acme"));
    }

    @Test
    void unknownTenantSlugReturns404() throws Exception {
        mvc.perform(get("/api/t/does-not-exist/_debug/tenant"))
           .andExpect(status().isNotFound());
    }

    @Test
    void suspendedTenantReturns404() throws Exception {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setSlug("suspended-co");
        t.setName("Suspended Co");
        t.setStatus(TenantStatus.SUSPENDED);
        tenants.save(t);

        mvc.perform(get("/api/t/suspended-co/_debug/tenant"))
           .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.tenancy.TenantResolutionTest"`
Expected: FAIL — no filter, no debug endpoint.

- [ ] **Step 3: Implement the resolver**

`tenancy/TenantResolver.java`:

```java
package co.ara.onboarding.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

public interface TenantResolver {
    Optional<String> resolveSlug(HttpServletRequest request);
}
```

`tenancy/PathPrefixTenantResolver.java`:

```java
package co.ara.onboarding.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PathPrefixTenantResolver implements TenantResolver {

    private static final Pattern TENANT_PATH =
            Pattern.compile("^/api/t/([a-z0-9][a-z0-9-]{0,62})(/.*)?$");

    @Override
    public Optional<String> resolveSlug(HttpServletRequest request) {
        Matcher m = TENANT_PATH.matcher(request.getRequestURI());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
```

- [ ] **Step 4: Implement the filter and exception type**

`tenancy/UnknownTenantException.java`:

```java
package co.ara.onboarding.tenancy;

public class UnknownTenantException extends RuntimeException {
    public UnknownTenantException(String slug) {
        super("Unknown or inactive tenant: " + slug);
    }
}
```

`tenancy/TenantContextFilter.java`:

```java
package co.ara.onboarding.tenancy;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(10)
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantResolver resolver;
    private final TenantRepository tenants;

    public TenantContextFilter(TenantResolver resolver, TenantRepository tenants) {
        this.resolver = resolver;
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var slug = resolver.resolveSlug(request);
        if (slug.isPresent()) {
            Tenant tenant = tenants.findBySlug(slug.get())
                    .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                    .orElseThrow(() -> new UnknownTenantException(slug.get()));
            TenantContext.set(tenant.getId());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
```

The `finally` block is not optional. Servlet threads are pooled; leaving a tenant bound would leak it into an unrelated request.

Note that `tenants.findBySlug` runs before any tenant is bound. That is correct — `tenant` is the registry itself and is deliberately not RLS-protected (Task 2).

- [ ] **Step 5: Implement the exception handler**

`platform/ApiExceptionHandler.java`:

```java
package co.ara.onboarding.platform;

import co.ara.onboarding.tenancy.UnknownTenantException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnknownTenantException.class)
    ProblemDetail unknownTenant(UnknownTenantException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
    }

    /** Out-of-scope records surface as absent, never as forbidden (spec 6.8). */
    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Forbidden");
    }
}
```

Both handlers return a bare `"Not found"` detail. Do not include the resource type or id — a differentiated message reintroduces the existence leak that returning 404 exists to prevent.

- [ ] **Step 6: Add the debug endpoint used by the test**

`tenancy/TenantDebugController.java`:

```java
package co.ara.onboarding.tenancy;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/t/{tenantSlug}/_debug")
public class TenantDebugController {

    private final TenantRepository tenants;

    public TenantDebugController(TenantRepository tenants) { this.tenants = tenants; }

    @GetMapping("/tenant")
    public String currentTenant() {
        return tenants.findById(TenantContext.getRequired()).orElseThrow().getSlug();
    }
}
```

This controller is removed in Task 20 once real endpoints cover the pipeline. Leave a `// TODO(task-20): remove` comment on the class so it is not forgotten.

- [ ] **Step 7: Permit the debug and auth endpoints in Spring Security**

`platform/SecurityConfig.java`:

```java
package co.ara.onboarding.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())   // stateless bearer tokens; refresh cookie is SameSite=Strict
            .sessionManagement(s -> s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())  // tightened in Task 14
            .build();
    }
}
```

- [ ] **Step 8: Bind the tenant to the database session on every transaction**

`TenantContextFilter` sets the thread-local, but RLS reads `app.tenant_id` on the **database** connection. Without this step the filter looks correct while RLS silently sees no tenant and every query returns nothing — or worse, a future change to a permissive policy would return everything.

`tenancy/TenantTransactionBinder.java`:

```java
package co.ara.onboarding.tenancy;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * Applies the current tenant to the JDBC connection at the start of every
 * transactional method. set_config(..., true) is transaction-scoped, so a
 * pooled connection cannot carry a tenant into the next request.
 */
@Aspect
@Component
@Order(200)   // MUST be a larger number than the transaction advisor's order — see below
public class TenantTransactionBinder {

    private final TenantConnectionCustomizer binder;

    public TenantTransactionBinder(TenantConnectionCustomizer binder) { this.binder = binder; }

    @Before("@annotation(org.springframework.transaction.annotation.Transactional) "
          + "|| @within(org.springframework.transaction.annotation.Transactional)")
    public void bindTenant() {
        UUID tenantId = TenantContext.getOrNull();
        if (tenantId != null) binder.bind(tenantId);
    }
}
```

**The ordering is the whole trick, and getting it backwards fails silently.** In Spring, a *lower* order value means *higher* precedence, which means the advice runs *further out* — before the transaction begins. Spring's transaction advisor defaults to `Ordered.LOWEST_PRECEDENCE`, so an aspect at `@Order(0)` would run outside the transaction entirely. `bind()` would then open its own short-lived transaction, apply `set_config('app.tenant_id', …, true)` — which is transaction-scoped — and commit, throwing the setting away before the real transaction ever starts. Every RLS-protected query would return zero rows, and because RLS fails closed, the symptom is "everything is mysteriously empty" rather than an error.

So give the transaction advisor an explicit, higher precedence and place the binder inside it.

`platform/TransactionConfig.java`:

```java
package co.ara.onboarding.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pins the transaction advisor's order so TenantTransactionBinder (@Order(200))
 * runs INSIDE the transaction it is binding the tenant to.
 */
@Configuration
@EnableTransactionManagement(order = 100)
public class TransactionConfig {}
```

And make the failure loud rather than silent by requiring an active transaction in `TenantConnectionCustomizer.bind` — change its annotation from `@Transactional` to:

```java
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(UUID tenantId) {
```

With `MANDATORY`, a mis-ordered aspect throws `IllegalTransactionStateException` on the first request instead of quietly returning empty result sets for the rest of the project.

- [ ] **Step 9: Write a test proving the binding actually reaches the database**

Add to `TenantResolutionTest`:

```java
    @Test
    void requestScopedTransactionSetsAppTenantIdInPostgres() throws Exception {
        mvc.perform(get("/api/t/acme/_debug/tenant-setting"))
           .andExpect(status().isOk())
           .andExpect(content().string(
                   tenants.findBySlug("acme").orElseThrow().getId().toString()));
    }
```

Add the matching debug endpoint to `TenantDebugController`, reading the setting back from PostgreSQL rather than from Java:

```java
    @GetMapping("/tenant-setting")
    @Transactional(readOnly = true)
    public String currentTenantSetting() {
        return jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class);
    }
```

Reading it back from the database is the point — asserting the Java thread-local would prove nothing about RLS.

- [ ] **Step 10: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.tenancy.TenantResolutionTest"`
Expected: PASS — all four tests.

- [ ] **Step 11: Commit**

```bash
git add backend/
git commit -m "feat: resolve tenant from path prefix and bind per request"
```

---

### Task 5: Identity module

Departments, teams, users, and platform admins. Departments and teams exist now because the `DEPARTMENT` and `TEAM` scopes in Task 12 are unresolvable without them.

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__identity.sql`
- Create: `backend/src/main/java/co/ara/onboarding/identity/Department.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/Team.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/AppUser.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/UserType.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/UserStatus.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/PlatformAdmin.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/*Repository.java`
- Test: `backend/src/test/java/co/ara/onboarding/identity/IdentityPersistenceTest.java`

**Interfaces:**
- Consumes: `TenantScopedEntity`, `TenantContext`, `TenantConnectionCustomizer`.
- Produces:
  - `AppUser` — `email`, `passwordHash`, `userType`, `status`, `fullName`, `departmentId`, `mfaEnabled`, `mfaSecret`, `lastLoginAt`, and `Set<UUID> teamIds` via `@ElementCollection` on `team_member`.
  - `UserType` — `INTERNAL`, `PORTAL`.
  - `UserStatus` — `INVITED`, `ACTIVE`, `SUSPENDED`, `DEACTIVATED`.
  - `AppUserRepository.findByTenantIdAndEmailIgnoreCase(UUID, String)`.
  - `PlatformAdmin` — **not** tenant-scoped; `email`, `passwordHash`, `fullName`, `enabled`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/co/ara/onboarding/identity/IdentityPersistenceTest.java`:

```java
package co.ara.onboarding.identity;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityPersistenceTest extends PostgresTestBase {

    @Autowired AppUserRepository users;
    @Autowired TenantFixture fixture;

    @Test
    void emailIsUniqueWithinTenantButNotAcrossTenants() {
        UUID tenantA = fixture.createTenant("alpha");
        UUID tenantB = fixture.createTenant("beta");

        fixture.runAs(tenantA, () -> users.save(newUser(tenantA, "shared@example.com")));
        fixture.runAs(tenantB, () -> users.save(newUser(tenantB, "shared@example.com")));

        fixture.runAs(tenantA, () ->
            assertThatThrownBy(() -> users.saveAndFlush(newUser(tenantA, "shared@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class));
    }

    @Test
    void findsUserByEmailWithinTenant() {
        UUID tenant = fixture.createTenant("gamma");
        fixture.runAs(tenant, () -> {
            users.save(newUser(tenant, "person@example.com"));
            assertThat(users.findByTenantIdAndEmailIgnoreCase(tenant, "PERSON@example.com"))
                    .isPresent();
        });
    }

    private AppUser newUser(UUID tenantId, String email) {
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setTenantId(tenantId);
        u.setEmail(email);
        u.setPasswordHash("x");
        u.setFullName("Test Person");
        u.setUserType(UserType.INTERNAL);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }
}
```

- [ ] **Step 2: Write the shared test fixture**

`backend/src/test/java/co/ara/onboarding/support/TenantFixture.java`:

```java
package co.ara.onboarding.support;

import co.ara.onboarding.tenancy.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Component
public class TenantFixture {

    private final TenantRepository tenants;
    private final TenantConnectionCustomizer binder;
    private final TransactionTemplate tx;

    public TenantFixture(TenantRepository tenants,
                         TenantConnectionCustomizer binder,
                         TransactionTemplate tx) {
        this.tenants = tenants;
        this.binder = binder;
        this.tx = tx;
    }

    public UUID createTenant(String slug) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setSlug(slug);
        t.setName(slug);
        t.setStatus(TenantStatus.ACTIVE);
        return tenants.save(t).getId();
    }

    /** Runs the action in a transaction with the tenant bound to both context and connection. */
    public void runAs(UUID tenantId, Runnable action) {
        TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(status -> {
            binder.bind(tenantId);
            action.run();
        }));
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.identity.IdentityPersistenceTest"`
Expected: FAIL — identity entities do not exist.

- [ ] **Step 4: Write the migration**

`backend/src/main/resources/db/migration/V4__identity.sql`:

```sql
CREATE TABLE department (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    name         varchar(255) NOT NULL,
    description  text,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE TABLE team (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    department_id  uuid REFERENCES department(id),
    name           varchar(255) NOT NULL,
    description    text,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE TABLE app_user (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    email          varchar(320) NOT NULL,
    password_hash  varchar(255),
    user_type      varchar(16)  NOT NULL,
    status         varchar(16)  NOT NULL,
    full_name      varchar(255) NOT NULL,
    department_id  uuid REFERENCES department(id),
    mfa_enabled    boolean      NOT NULL DEFAULT false,
    mfa_secret     varchar(255),
    last_login_at  timestamptz,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL
);

-- Email is unique WITHIN a tenant, not globally (spec 5.2).
CREATE UNIQUE INDEX app_user_tenant_email_key
    ON app_user (tenant_id, lower(email));

CREATE TABLE team_member (
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    user_id    uuid NOT NULL REFERENCES app_user(id),
    team_id    uuid NOT NULL REFERENCES team(id),
    PRIMARY KEY (user_id, team_id)
);

-- Deliberately NOT tenant-scoped: vendor-side administration (spec 5.2).
CREATE TABLE platform_admin (
    id             uuid PRIMARY KEY,
    email          varchar(320) NOT NULL UNIQUE,
    password_hash  varchar(255) NOT NULL,
    full_name      varchar(255) NOT NULL,
    enabled        boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);

SELECT enable_tenant_rls('department');
SELECT enable_tenant_rls('team');
SELECT enable_tenant_rls('app_user');
SELECT enable_tenant_rls('team_member');

GRANT SELECT, INSERT, UPDATE ON department, team, app_user, team_member TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON platform_admin TO onboarding_app;

-- team_member is a pure join table: changing someone's teams means removing
-- rows. Users, departments and teams are deactivated, never deleted.
GRANT DELETE ON team_member TO onboarding_app;
```

- [ ] **Step 5: Implement the enums**

```java
package co.ara.onboarding.identity;
public enum UserType { INTERNAL, PORTAL }
```

```java
package co.ara.onboarding.identity;
public enum UserStatus { INVITED, ACTIVE, SUSPENDED, DEACTIVATED }
```

- [ ] **Step 6: Implement `AppUser`**

```java
package co.ara.onboarding.identity;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser extends TenantScopedEntity {

    @Column(nullable = false) private String email;
    @Column(name = "password_hash") private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false) private UserType userType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private UserStatus status;

    @Column(name = "full_name", nullable = false) private String fullName;
    @Column(name = "department_id") private UUID departmentId;
    @Column(name = "mfa_enabled", nullable = false) private boolean mfaEnabled = false;
    @Column(name = "mfa_secret") private String mfaSecret;
    @Column(name = "last_login_at") private Instant lastLoginAt;

    /**
     * tenant_id must be a second join column. team_member.tenant_id is NOT NULL
     * and RLS-checked, and a single-join-column mapping emits
     * INSERT INTO team_member (user_id, team_id) — omitting tenant_id entirely,
     * so every write fails the NOT NULL constraint. Hibernate populates both
     * columns from the owner when they are declared here.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "team_member",
            joinColumns = {
                @JoinColumn(name = "user_id",   referencedColumnName = "id"),
                @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id")
            })
    @Column(name = "team_id")
    private Set<UUID> teamIds = new HashSet<>();

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public UserType getUserType() { return userType; }
    public void setUserType(UserType userType) { this.userType = userType; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public UUID getDepartmentId() { return departmentId; }
    public void setDepartmentId(UUID departmentId) { this.departmentId = departmentId; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public void setMfaEnabled(boolean mfaEnabled) { this.mfaEnabled = mfaEnabled; }
    public String getMfaSecret() { return mfaSecret; }
    public void setMfaSecret(String mfaSecret) { this.mfaSecret = mfaSecret; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Set<UUID> getTeamIds() { return teamIds; }
    public void setTeamIds(Set<UUID> teamIds) { this.teamIds = teamIds; }
}
```

- [ ] **Step 7: Implement `Department`, `Team`, `PlatformAdmin` and the repositories**

`Department` and `Team` follow the same shape as `AppUser`: extend `TenantScopedEntity`, map `name`, `description`, and (for `Team`) `departmentId`. `PlatformAdmin` extends `BaseEntity` — **not** `TenantScopedEntity` — and maps `email`, `passwordHash`, `fullName`, `enabled`.

Repositories:

```java
package co.ara.onboarding.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);
}
```

```java
package co.ara.onboarding.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {}
```

```java
package co.ara.onboarding.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {}
```

```java
package co.ara.onboarding.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, UUID> {
    Optional<PlatformAdmin> findByEmailIgnoreCase(String email);
}
```

- [ ] **Step 8: Prove the Hibernate filter is actually registered and filtering**

Carried forward from Task 4's review. `@FilterDef`/`@Filter` live on `TenantScopedEntity`, a `@MappedSuperclass`, and Hibernate only registers the filter once metadata scanning walks a **concrete entity** that extends it. Until Task 5 there was no such entity, so `TenantConnectionCustomizer.bind` guards the call with a `getDefinedFilterNames().contains(...)` check.

`AppUser` is the first real entity, so this is the first moment the guard can be verified. A guard that silently stays false forever would remove the application-layer half of the two-layer isolation design while everything still appeared to work — RLS underneath would keep returning correct results and hide it.

`backend/src/test/java/co/ara/onboarding/tenancy/HibernateFilterTest.java`:

```java
package co.ara.onboarding.tenancy;

import co.ara.onboarding.support.PostgresTestBase;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class HibernateFilterTest extends PostgresTestBase {

    @Autowired EntityManager entityManager;

    @Test
    void tenantFilterIsRegisteredNowThatAConcreteEntityExists() {
        var names = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getDefinedFilterNames();

        assertThat(names)
            .as("the guard in TenantConnectionCustomizer must now pass, not silently skip")
            .contains("tenantFilter");
    }
}
```

- [ ] **Step 9: Prove the filter actually excludes other tenants' rows**

Registration is not the same as enforcement. This test asserts the filter *works* through JPA, which no existing test does — `RlsIsolationTest` exercises RLS through raw JDBC only.

Add to `HibernateFilterTest` a test that creates users in two tenants, then reads through `AppUserRepository` inside `fixture.runAs(tenantA, …)` and asserts only tenant A's users come back. Because RLS would also produce that result, temporarily disabling the filter must **not** change the outcome — so to prove the filter specifically, assert on the generated SQL instead: enable Hibernate's `SessionFactory` statistics or use a query-count/SQL-capture approach and confirm the emitted SQL contains a `tenant_id = ?` predicate that the application added.

If capturing SQL proves awkward, the acceptable fallback is asserting `session.getEnabledFilter("tenantFilter")` is non-null and carries the expected `tenantId` parameter after `bind()` runs. State in the report which approach you used and why.

- [ ] **Step 10: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.identity.*" --tests "co.ara.onboarding.tenancy.HibernateFilterTest"`
Expected: PASS

- [ ] **Step 11: Commit**

```bash
git add backend/
git commit -m "feat: add identity module with departments, teams, users and platform admins"
```

---

### Task 6: Audit substrate

A finite action registry, an append-only event table, and a recorder that captures request context automatically. Built before authorization because authorization changes are themselves auditable events.

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__audit.sql`
- Create: `backend/src/main/java/co/ara/onboarding/audit/AuditAction.java`
- Create: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Create: `backend/src/main/java/co/ara/onboarding/audit/AuditEvent.java`
- Create: `backend/src/main/java/co/ara/onboarding/audit/AuditEventRepository.java`
- Create: `backend/src/main/java/co/ara/onboarding/audit/AuditRecorder.java`
- Create: `backend/src/main/java/co/ara/onboarding/platform/RequestAuditContext.java`
- Test: `backend/src/test/java/co/ara/onboarding/audit/AuditRecorderTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/audit/AuditAppendOnlyTest.java`

**Interfaces:**
- Consumes: `TenantScopedEntity`, `TenantFixture`.
- Produces:
  - `AuditAction` — `record AuditAction(String key, boolean timelineVisible)`.
  - `AuditActions` — static registry: `AuditActions.all()`, `AuditActions.byKey(String)`. Seeded here with `tenant.created`, `user.created`, `user.role_assigned`, `role.created`, `role.updated`, `role.disabled`, `auth.login_succeeded`, `auth.login_failed`, `auth.refresh_reuse_detected`, `customer.created`, `customer.updated`, `customer.deactivated`, `invitation.sent`, `invitation.accepted`.
  - `AuditRecorder.record(AuditAction action, String resourceType, UUID resourceId, String summary, Object payload)`.
  - `RequestAuditContext` — request-scoped bean exposing `actorUserId()`, `actorType()`, `ip()`, `userAgent()`, `requestId()`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/co/ara/onboarding/audit/AuditRecorderTest.java`:

```java
package co.ara.onboarding.audit;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AuditRecorderTest extends PostgresTestBase {

    @Autowired AuditRecorder recorder;
    @Autowired AuditEventRepository events;
    @Autowired TenantFixture fixture;

    @Test
    void recordsEventWithTenantAndAction() {
        UUID tenant = fixture.createTenant("audit-co");
        UUID resourceId = UUID.randomUUID();

        fixture.runAs(tenant, () -> recorder.record(
                AuditActions.CUSTOMER_CREATED, "customer", resourceId,
                "Created customer Acme", Map.of("displayName", "Acme")));

        fixture.runAs(tenant, () -> {
            var all = events.findAll();
            assertThat(all).hasSize(1);
            var e = all.get(0);
            assertThat(e.getTenantId()).isEqualTo(tenant);
            assertThat(e.getAction()).isEqualTo("customer.created");
            assertThat(e.getResourceId()).isEqualTo(resourceId);
            assertThat(e.isTimelineVisible()).isTrue();
        });
    }

    @Test
    void rejectsActionOutsideRegistry() {
        assertThat(AuditActions.byKey("not.a.real.action")).isEmpty();
    }
}
```

`backend/src/test/java/co/ara/onboarding/audit/AuditAppendOnlyTest.java`:

```java
package co.ara.onboarding.audit;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditAppendOnlyTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void applicationRoleCannotUpdateOrDeleteAuditEvents() {
        // hasStackTraceContaining, not hasMessageContaining: JdbcTemplate wraps the
        // driver error in a BadSqlGrammarException whose own message is generic,
        // so the Postgres text only appears on the cause.
        assertThatThrownBy(() -> jdbc.execute("UPDATE audit_event SET summary = 'tampered'"))
                .hasStackTraceContaining("permission denied for table audit_event");

        assertThatThrownBy(() -> jdbc.execute("DELETE FROM audit_event"))
                .hasStackTraceContaining("permission denied for table audit_event");
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.audit.*"`
Expected: FAIL — no audit table or classes.

- [ ] **Step 3: Write the migration**

`backend/src/main/resources/db/migration/V5__audit.sql`:

```sql
CREATE TABLE audit_event (
    id               uuid        NOT NULL,
    tenant_id        uuid        NOT NULL REFERENCES tenant(id),
    occurred_at      timestamptz NOT NULL,
    actor_type       varchar(24) NOT NULL,
    actor_user_id    uuid,
    action           varchar(64) NOT NULL,
    resource_type    varchar(64) NOT NULL,
    resource_id      uuid,
    summary          text        NOT NULL,
    payload          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    timeline_visible boolean     NOT NULL,
    ip               varchar(64),
    user_agent       text,
    request_id       varchar(64),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Initial partitions. A scheduled job creates future ones in sub-project 6;
-- until then, create partitions manually or extend this list.
CREATE TABLE audit_event_2026_08 PARTITION OF audit_event
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE audit_event_2026_09 PARTITION OF audit_event
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE audit_event_default PARTITION OF audit_event DEFAULT;

CREATE INDEX audit_event_tenant_resource_idx
    ON audit_event (tenant_id, resource_type, resource_id, occurred_at DESC);

SELECT enable_tenant_rls('audit_event');

-- Append-only: no UPDATE, no DELETE. This is a permission, not a convention (spec 8.5).
GRANT SELECT, INSERT ON audit_event TO onboarding_app;
REVOKE UPDATE, DELETE ON audit_event FROM onboarding_app;
```

The explicit `REVOKE` is required for `UPDATE`, which `ALTER DEFAULT PRIVILEGES` in Task 2 does grant on new tables. `DELETE` is already deny-by-default after `V2_1`, so revoking it here is redundant — keep it anyway, because this table's append-only guarantee is important enough to state at its own definition rather than rely on a default set three migrations earlier.

- [ ] **Step 4: Implement the action registry**

```java
package co.ara.onboarding.audit;

public record AuditAction(String key, boolean timelineVisible) {}
```

```java
package co.ara.onboarding.audit;

import java.util.*;

public final class AuditActions {

    // MUST be declared before the constants below. Static initialisers run in
    // declaration order, and each of() call writes into this map — declaring it
    // afterwards leaves it null at that point and the class fails to load with
    // ExceptionInInitializerError.
    private static final Map<String, AuditAction> BY_KEY = new LinkedHashMap<>();

    public static final AuditAction TENANT_CREATED            = of("tenant.created", false);
    public static final AuditAction USER_CREATED              = of("user.created", false);
    public static final AuditAction USER_ROLE_ASSIGNED        = of("user.role_assigned", false);
    public static final AuditAction ROLE_CREATED              = of("role.created", false);
    public static final AuditAction ROLE_UPDATED              = of("role.updated", false);
    public static final AuditAction ROLE_DISABLED             = of("role.disabled", false);
    public static final AuditAction LOGIN_SUCCEEDED           = of("auth.login_succeeded", false);
    public static final AuditAction LOGIN_FAILED              = of("auth.login_failed", false);
    public static final AuditAction REFRESH_REUSE_DETECTED    = of("auth.refresh_reuse_detected", false);
    public static final AuditAction CUSTOMER_CREATED          = of("customer.created", true);
    public static final AuditAction CUSTOMER_UPDATED          = of("customer.updated", true);
    public static final AuditAction CUSTOMER_DEACTIVATED      = of("customer.deactivated", true);
    public static final AuditAction INVITATION_SENT           = of("invitation.sent", true);
    public static final AuditAction INVITATION_ACCEPTED       = of("invitation.accepted", true);

    private static AuditAction of(String key, boolean timelineVisible) {
        AuditAction a = new AuditAction(key, timelineVisible);
        BY_KEY.put(key, a);
        return a;
    }

    private AuditActions() {}

    public static Collection<AuditAction> all() { return List.copyOf(BY_KEY.values()); }

    public static Optional<AuditAction> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
```

- [ ] **Step 5: Implement `RequestAuditContext`**

```java
package co.ara.onboarding.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import java.util.Optional;
import java.util.UUID;

@Component
@RequestScope
public class RequestAuditContext {

    public enum ActorType { USER, SYSTEM, PLATFORM_ADMIN }

    private UUID actorUserId;
    private ActorType actorType = ActorType.SYSTEM;
    private final String ip;
    private final String userAgent;
    private final String requestId = UUID.randomUUID().toString();

    public RequestAuditContext(Optional<HttpServletRequest> request) {
        this.ip = request.map(HttpServletRequest::getRemoteAddr).orElse(null);
        this.userAgent = request.map(r -> r.getHeader("User-Agent")).orElse(null);
    }

    public void setActor(UUID userId, ActorType type) {
        this.actorUserId = userId;
        this.actorType = type;
    }

    public UUID actorUserId() { return actorUserId; }
    public ActorType actorType() { return actorType; }
    public String ip() { return ip; }
    public String userAgent() { return userAgent; }
    public String requestId() { return requestId; }
}
```

- [ ] **Step 6: Implement `AuditEvent`, repository, and `AuditRecorder`**

`AuditEvent` extends `TenantScopedEntity` and maps every column in the migration. Use `@Column(columnDefinition = "jsonb") String payload` with Hibernate's `@JdbcTypeCode(SqlTypes.JSON)`.

**On the primary key mismatch, which will look wrong and is not.** PostgreSQL requires the partition key to be part of any primary key, so the table declares `PRIMARY KEY (id, occurred_at)` while the entity inherits a single `@Id UUID id` from `BaseEntity`. Leave it that way:

- `id` is a UUIDv7 and is unique on its own, so a single-column `@Id` is a correct entity identity.
- Hibernate's `validate` mode checks columns and types, not primary-key composition, so this does not fail startup.
- Do **not** "fix" it with an `@IdClass` or `@EmbeddedId` of `(id, occurredAt)`. That would push `occurredAt` into every association and lookup for no benefit, and audit rows are written far more often than they are fetched by id.

The one real consequence: `findById` cannot prune partitions and will scan all of them. That is acceptable here because audit events are read through time-bounded, tenant-scoped queries, never by bare id in any hot path.

```java
package co.ara.onboarding.audit;

import co.ara.onboarding.platform.RequestAuditContext;
import co.ara.onboarding.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Component
public class AuditRecorder {

    private final AuditEventRepository events;
    private final RequestAuditContext context;
    private final ObjectMapper json;

    public AuditRecorder(AuditEventRepository events,
                         RequestAuditContext context,
                         ObjectMapper json) {
        this.events = events;
        this.context = context;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String resourceType, UUID resourceId,
                       String summary, Object payload) {
        AuditEvent e = new AuditEvent();
        e.setId(UUID.randomUUID());
        e.setTenantId(TenantContext.getRequired());
        e.setOccurredAt(Instant.now());
        e.setAction(action.key());
        e.setTimelineVisible(action.timelineVisible());
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setSummary(summary);
        e.setPayload(writeJson(payload));
        e.setActorUserId(context.actorUserId());
        e.setActorType(context.actorType().name());
        e.setIp(context.ip());
        e.setUserAgent(context.userAgent());
        e.setRequestId(context.requestId());
        events.save(e);
    }

    private String writeJson(Object payload) {
        try {
            return payload == null ? "{}" : json.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Audit payload is not serializable", ex);
        }
    }
}
```

`Propagation.MANDATORY` is deliberate: an audit event must be written in the same transaction as the change it records, so a rolled-back operation cannot leave a phantom audit entry.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.audit.*"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/
git commit -m "feat: add append-only audit substrate with finite action registry"
```

---

### Task 7: Structural guards — RLS meta-test and ArchUnit rules

These two tests are the reason the isolation and authorization design survives sub-projects 2 through 10. They must exist before the authorization code they police.

**Files:**
- Create: `backend/src/test/java/co/ara/onboarding/architecture/RlsCoverageTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java`

**Interfaces:**
- Consumes: everything built so far.
- Produces: three always-on structural tests. `AuthorizationCoverageTest` is written now but asserts against `@RequirePermission`, which arrives in Task 13; until then it is `@Disabled` with an explicit reason.

- [ ] **Step 1: Write the RLS coverage meta-test**

`backend/src/test/java/co/ara/onboarding/architecture/RlsCoverageTest.java`:

```java
package co.ara.onboarding.architecture;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deny-by-default: every table in the public schema must be RLS-protected
 * unless it is on this explicitly reviewed allowlist (spec 11.2).
 * Adding an entry here is a deliberate act. Do not add one to make a test pass.
 */
class RlsCoverageTest extends PostgresTestBase {

    private static final Set<String> NOT_TENANT_SCOPED = Set.of(
            "tenant",                  // the tenant registry itself
            "platform_admin",          // vendor-side administration
            "permission",              // global catalog mirror
            "flyway_schema_history"
    );

    @Autowired JdbcTemplate jdbc;

    @Test
    void everyTenantScopedTableHasForcedRlsAndAPolicy() {
        List<String> tables = jdbc.queryForList("""
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p')
              AND c.relispartition = false
            """, String.class);

        for (String table : tables) {
            if (NOT_TENANT_SCOPED.contains(table) || table.startsWith("rls_probe")) continue;

            Boolean rlsEnabled = jdbc.queryForObject(
                    "SELECT relrowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table);
            Boolean rlsForced = jdbc.queryForObject(
                    "SELECT relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table);
            Integer policies = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_policies WHERE tablename = ?", Integer.class, table);

            assertThat(rlsEnabled).as("%s must have RLS enabled", table).isTrue();
            assertThat(rlsForced).as("%s must have RLS FORCED", table).isTrue();
            assertThat(policies).as("%s must have a policy", table).isPositive();
        }
    }

    @Test
    void everyTenantScopedTableHasATenantIdColumn() {
        List<String> tables = jdbc.queryForList("""
            SELECT c.relname FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND c.relispartition = false
            """, String.class);

        for (String table : tables) {
            if (NOT_TENANT_SCOPED.contains(table) || table.startsWith("rls_probe")) continue;
            Integer hasColumn = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = ? AND column_name = 'tenant_id'
                """, Integer.class, table);
            assertThat(hasColumn).as("%s must have tenant_id", table).isEqualTo(1);
        }
    }
}
```

- [ ] **Step 2: Run it — it should pass against the current schema**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.architecture.RlsCoverageTest"`
Expected: PASS. If it fails, a table from Task 5 or 6 is missing its `enable_tenant_rls` call — fix the migration with a new `V*` file, never by editing a committed one.

- [ ] **Step 3: Prove the guard actually catches an unprotected table**

Temporarily add to `V5__audit.sql` a table with no `enable_tenant_rls` call, re-run, confirm FAIL, then revert. A structural guard you have never seen fail is a guard you cannot trust.

- [ ] **Step 4: Write the module boundary test**

`backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java`:

```java
package co.ara.onboarding.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = "co.ara.onboarding",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule noCyclesBetweenModules =
            slices().matching("co.ara.onboarding.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule servicesDoNotDependOnControllers =
            noClasses().that().haveSimpleNameEndingWith("Service")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .because("controllers are an entry point, never a dependency of the domain");

    /**
     * Carried forward from Task 4's review. Spring Data repository proxies get
     * their own transaction handling and do NOT trigger TenantTransactionBinder,
     * so a repository called outside an enclosing @Transactional service method
     * runs with no tenant bound. RLS then fails closed and the query returns
     * nothing — silently, with no error to follow.
     *
     * Controllers must therefore never touch a repository directly.
     */
    @ArchTest
    static final ArchRule controllersDoNotUseRepositoriesDirectly =
            noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("a repository call outside a @Transactional service has no tenant bound");
}
```

`TenantDebugController` is the one current violation — it injects `TenantRepository`. It is deleted in Task 20, so either exclude it by name with a comment referencing that task, or move its lookup behind a small service. Do not weaken the rule.

**Do not use `layeredArchitecture().definedBy("co.ara.onboarding..*Controller")` here.** `definedBy(String...)` takes *package* identifiers, not class-name patterns, so `*Controller` would be read as a package segment and match nothing — producing a layer that is empty and a rule that can never fail. This project also has no `..controller..` package; controllers live directly in their domain module. The `noClasses()` form above expresses the same intent and actually binds to real classes.

Verify it can fail: temporarily add a field of a `*Controller` type to any `*Service`, run the test, confirm FAIL, then revert.

- [ ] **Step 5: Write the authorization coverage test, disabled for now**

`backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java`:

```java
package co.ara.onboarding.architecture;

import co.ara.onboarding.authz.RequirePermission;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

@AnalyzeClasses(
        packages = "co.ara.onboarding",
        importOptions = ImportOption.DoNotIncludeTests.class)
class AuthorizationCoverageTest {

    /**
     * Every public method on a *Service that a controller can reach must carry
     * an explicit permission gate (spec 6.10).
     */
    @ArchTest
    static final ArchRule serviceMethodsAreGated =
            methods().that().arePublic()
                     .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Service")
                     .and().areDeclaredInClassesThat().resideInAPackage("co.ara.onboarding..")
                     .should().beAnnotatedWith(RequirePermission.class)
                     .because("authorization must be central, not per-endpoint");
}
```

This will not compile until Task 13 creates `RequirePermission`. Create the annotation now as an empty marker so the build stays green, and Task 13 gives it behaviour:

```java
package co.ara.onboarding.authz;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequirePermission {
    String value();
}
```

Services that legitimately need no gate — `TenantProvisioningService`, which runs before any tenant user exists — are excluded by an explicit, commented clause naming the class:

```java
                     .and().areNotDeclaredIn(TenantProvisioningService.class)
```

Never by deleting or weakening the rule. There is deliberately **no** `PermissionKeys.PLATFORM_ADMIN` catch-all: a permission that means "skip the check" would be indistinguishable from a real grant in the catalog, and every future ungated service would reach for it. Platform-admin endpoints are secured at the HTTP layer instead (Task 22 Step 9).

- [ ] **Step 6: Run all architecture tests**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.architecture.*"`
Expected: PASS (`AuthorizationCoverageTest` will pass trivially while no `*Service` classes exist yet).

- [ ] **Step 7: Commit**

```bash
git add backend/
git commit -m "test: add RLS coverage meta-test and ArchUnit structural guards"
```

---

### Task 8: Permission catalog

The finite, code-defined catalog, mirrored to a database table at startup. Tenants configure roles; they never invent permissions (spec §6.2).

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__permission.sql`
- Create: `backend/src/main/java/co/ara/onboarding/authz/Scope.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/Permission.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/PermissionKeys.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/PermissionCatalog.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/PermissionSyncRunner.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/PermissionCatalogTest.java`

**Interfaces:**
- Consumes: `PostgresTestBase`.
- Produces:
  - `Scope` — `ALL`, `DEPARTMENT`, `TEAM`, `ASSIGNED`. Exactly four. Do not add more (spec §6.3).
  - `Permission` — `record Permission(String key, String category, String resourceType, String description, Set<Scope> allowedScopes)`. `resourceType` is `null` for permissions that are `ALL`-only and therefore have no record to scope against; Task 11 requires a registered descriptor for every permission whose `resourceType` is non-null.
  - `PermissionCatalog.all()`, `PermissionCatalog.byKey(String)`, `PermissionCatalog.allows(String key, Scope scope)`.
  - `PermissionKeys` — string constants used everywhere else in the codebase.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/co/ara/onboarding/authz/PermissionCatalogTest.java`:

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class PermissionCatalogTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void catalogIsMirroredToDatabaseAtStartup() {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM permission", Integer.class);
        assertThat(rows).isEqualTo(PermissionCatalog.all().size());
    }

    @Test
    void allOnlyPermissionsRejectNarrowerScopes() {
        assertThat(PermissionCatalog.allows(PermissionKeys.ROLE_MANAGE, Scope.ALL)).isTrue();
        assertThat(PermissionCatalog.allows(PermissionKeys.ROLE_MANAGE, Scope.TEAM)).isFalse();
        assertThat(PermissionCatalog.allows(PermissionKeys.ROLE_MANAGE, Scope.ASSIGNED)).isFalse();
    }

    @Test
    void recordScopedPermissionsAcceptAllFourScopes() {
        for (Scope s : Scope.values()) {
            assertThat(PermissionCatalog.allows(PermissionKeys.CUSTOMER_VIEW, s))
                    .as("customer.view should allow %s", s).isTrue();
        }
    }

    @Test
    void unknownPermissionKeyIsRejected() {
        assertThat(PermissionCatalog.byKey("made.up.permission")).isEmpty();
        assertThat(PermissionCatalog.allows("made.up.permission", Scope.ALL)).isFalse();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.PermissionCatalogTest"`
Expected: FAIL — catalog classes and `permission` table do not exist.

- [ ] **Step 3: Write the migration**

`backend/src/main/resources/db/migration/V6__permission.sql`:

```sql
-- Global catalog mirror. Not tenant-scoped: the catalog is identical for
-- every tenant. Listed on the RLS meta-test allowlist in Task 7.
CREATE TABLE permission (
    key             varchar(64) PRIMARY KEY,
    category        varchar(64) NOT NULL,
    resource_type   varchar(64),          -- NULL for ALL-only permissions
    description     text        NOT NULL,
    allowed_scopes  varchar(255) NOT NULL
);

-- No DELETE: orphaned permissions are logged and ignored, never auto-removed,
-- so a mistaken catalog removal stays revertible (spec 6.2).
GRANT SELECT, INSERT, UPDATE ON permission TO onboarding_app;
```

- [ ] **Step 4: Implement `Scope`, `Permission`, `PermissionKeys`, `PermissionCatalog`**

```java
package co.ara.onboarding.authz;

/** Exactly four scopes. Adding a fifth requires a PRD/QA requirement (spec 6.3). */
public enum Scope { ALL, DEPARTMENT, TEAM, ASSIGNED }
```

```java
package co.ara.onboarding.authz;

import java.util.Set;

/**
 * resourceType is null for ALL-only permissions, which have no record to scope
 * against. Any permission with a non-null resourceType must have a registered
 * ResourceAuthorizationDescriptor (Task 11) or the application refuses to start.
 */
public record Permission(String key, String category, String resourceType,
                         String description, Set<Scope> allowedScopes) {}
```

```java
package co.ara.onboarding.authz;

public final class PermissionKeys {
    public static final String TENANT_SETTINGS_VIEW = "tenant.settings.view";
    public static final String TENANT_SETTINGS_EDIT = "tenant.settings.edit";
    public static final String USER_VIEW            = "user.view";
    public static final String USER_MANAGE          = "user.manage";
    public static final String ROLE_VIEW            = "role.view";
    public static final String ROLE_MANAGE          = "role.manage";
    public static final String DEPARTMENT_MANAGE    = "department.manage";
    public static final String TEAM_MANAGE          = "team.manage";
    public static final String CUSTOMER_VIEW        = "customer.view";
    public static final String CUSTOMER_CREATE      = "customer.create";
    public static final String CUSTOMER_EDIT        = "customer.edit";
    public static final String CUSTOMER_DEACTIVATE  = "customer.deactivate";
    public static final String CONTACT_VIEW         = "contact.view";
    public static final String CONTACT_MANAGE       = "contact.manage";
    public static final String INVITATION_SEND      = "invitation.send";
    public static final String AUDIT_VIEW           = "audit.view";
    private PermissionKeys() {}
}
```

```java
package co.ara.onboarding.authz;

import java.util.*;
import static co.ara.onboarding.authz.PermissionKeys.*;
import static co.ara.onboarding.authz.Scope.*;

public final class PermissionCatalog {

    private static final Map<String, Permission> BY_KEY = new LinkedHashMap<>();

    private static final Set<Scope> ALL_ONLY   = EnumSet.of(ALL);
    private static final Set<Scope> ORG_SCOPES = EnumSet.of(ALL, DEPARTMENT, TEAM);
    private static final Set<Scope> RECORD     = EnumSet.allOf(Scope.class);

    static {
        //  key                  category     resourceType        description                       scopes
        add(TENANT_SETTINGS_VIEW, "tenant",   null,               "View tenant settings",           ALL_ONLY);
        add(TENANT_SETTINGS_EDIT, "tenant",   null,               "Edit tenant settings",           ALL_ONLY);
        add(USER_VIEW,            "identity", "app_user",         "View users",                     ORG_SCOPES);
        add(USER_MANAGE,          "identity", "app_user",         "Create and modify users",        ORG_SCOPES);
        add(ROLE_VIEW,            "authz",    null,               "View roles and grants",          ALL_ONLY);
        add(ROLE_MANAGE,          "authz",    null,               "Create and modify roles",        ALL_ONLY);
        add(DEPARTMENT_MANAGE,    "identity", null,               "Manage departments",             ALL_ONLY);
        add(TEAM_MANAGE,          "identity", null,               "Manage teams",                   ALL_ONLY);
        add(CUSTOMER_VIEW,        "customer", "customer",         "View customers",                 RECORD);
        add(CUSTOMER_CREATE,      "customer", null,               "Create customers",               ALL_ONLY);
        add(CUSTOMER_EDIT,        "customer", "customer",         "Edit customers",                 RECORD);
        add(CUSTOMER_DEACTIVATE,  "customer", "customer",         "Deactivate customers",           ORG_SCOPES);
        add(CONTACT_VIEW,         "customer", "customer_contact", "View customer contacts",         RECORD);
        add(CONTACT_MANAGE,       "customer", "customer_contact", "Manage customer contacts",       RECORD);
        add(INVITATION_SEND,      "customer", "customer_contact", "Send portal invitations",        RECORD);
        add(AUDIT_VIEW,           "audit",    "audit_event",      "View the audit log",             ORG_SCOPES);
    }

    private static void add(String key, String category, String resourceType,
                            String description, Set<Scope> scopes) {
        BY_KEY.put(key, new Permission(key, category, resourceType, description, Set.copyOf(scopes)));
    }

    private PermissionCatalog() {}

    public static Collection<Permission> all() { return List.copyOf(BY_KEY.values()); }

    public static Optional<Permission> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    public static boolean allows(String key, Scope scope) {
        return byKey(key).map(p -> p.allowedScopes().contains(scope)).orElse(false);
    }
}
```

`CUSTOMER_CREATE` is `ALL`-only on purpose: creation has no existing record to scope against, so a narrower scope would be meaningless.

- [ ] **Step 5: Implement the startup sync with orphan reporting**

```java
package co.ara.onboarding.authz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.stream.Collectors;

@Component
public class PermissionSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionSyncRunner.class);

    private final JdbcTemplate jdbc;

    public PermissionSyncRunner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        for (Permission p : PermissionCatalog.all()) {
            String scopes = p.allowedScopes().stream().map(Enum::name)
                             .sorted().collect(Collectors.joining(","));
            jdbc.update("""
                INSERT INTO permission (key, category, resource_type, description, allowed_scopes)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (key) DO UPDATE
                SET category = EXCLUDED.category,
                    resource_type = EXCLUDED.resource_type,
                    description = EXCLUDED.description,
                    allowed_scopes = EXCLUDED.allowed_scopes
                """, p.key(), p.category(), p.resourceType(), p.description(), scopes);
        }

        var known = PermissionCatalog.all().stream().map(Permission::key).toList();
        jdbc.query("SELECT key FROM permission", rs -> {
            String key = rs.getString("key");
            if (!known.contains(key)) {
                // Orphans are logged and ignored, never auto-deleted (spec 6.2),
                // so a mistaken catalog removal is revertible without data loss.
                log.warn("Orphaned permission '{}' remains in the database; "
                       + "it grants nothing until restored to the code catalog.", key);
            }
        });
    }
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.PermissionCatalogTest"`
Expected: PASS — all four tests.

- [ ] **Step 7: Commit**

```bash
git add backend/
git commit -m "feat: add code-defined permission catalog with startup sync"
```

---

### Task 9: Roles, grants, and scope validation

Tenant-owned roles with per-permission scope. Invalid permission/scope combinations are rejected at write time.

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__roles.sql`
- Create: `backend/src/main/java/co/ara/onboarding/authz/Role.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/RoleGrant.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/RoleRepository.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/UserRole.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/UserRoleRepository.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/InvalidGrantException.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/RoleService.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/RoleServiceTest.java`

**Interfaces:**
- Consumes: `PermissionCatalog`, `TenantScopedEntity`, `AuditRecorder`, `TenantFixture`.
- Produces:
  - `Role` — `name`, `description`, `systemTemplate` (boolean), `enabled` (boolean), `Set<RoleGrant> grants`.
  - `RoleGrant` — `permissionKey`, `scope`. Unique on `(role_id, permission_key)`.
  - `RoleService.createRole(String name, String description, Map<String,Scope> grants)`, `.updateGrants(UUID roleId, Map<String,Scope>)`, `.setEnabled(UUID roleId, boolean)`, `.assignRole(UUID userId, UUID roleId)`, `.deleteRole(UUID roleId)`.
  - `InvalidGrantException` — thrown on unknown permission key or disallowed scope; mapped to 400 in `ApiExceptionHandler`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/co/ara/onboarding/authz/RoleServiceTest.java`:

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleServiceTest extends PostgresTestBase {

    @Autowired RoleService roles;
    @Autowired RoleRepository roleRepository;
    @Autowired TenantFixture fixture;

    @Test
    void rejectsScopeNotAllowedForPermission() {
        UUID tenant = fixture.createTenant("scope-check");
        fixture.runAs(tenant, () ->
            assertThatThrownBy(() -> roles.createRole("Bad Role", "",
                    Map.of(PermissionKeys.ROLE_MANAGE, Scope.TEAM)))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("role.manage")
                .hasMessageContaining("TEAM"));
    }

    @Test
    void rejectsUnknownPermissionKey() {
        UUID tenant = fixture.createTenant("unknown-perm");
        fixture.runAs(tenant, () ->
            assertThatThrownBy(() -> roles.createRole("Bad Role", "",
                    Map.of("not.a.permission", Scope.ALL)))
                .isInstanceOf(InvalidGrantException.class));
    }

    @Test
    void acceptsValidGrantAndPersistsScope() {
        UUID tenant = fixture.createTenant("valid-grant");
        fixture.runAs(tenant, () -> {
            UUID roleId = roles.createRole("Team Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            Role saved = roleRepository.findById(roleId).orElseThrow();
            assertThat(saved.getGrants()).hasSize(1);
            assertThat(saved.getGrants().iterator().next().getScope()).isEqualTo(Scope.TEAM);
        });
    }

    @Test
    void deletionIsBlockedWhileUsersAreAssigned() {
        UUID tenant = fixture.createTenant("role-delete");
        fixture.runAs(tenant, () -> {
            UUID roleId = roles.createRole("In Use", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            UUID userId = fixture.createUser(tenant, "holder@example.com");
            roles.assignRole(userId, roleId);

            assertThatThrownBy(() -> roles.deleteRole(roleId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assigned");
        });
    }
}
```

- [ ] **Step 2: Extend `TenantFixture` with `createUser`**

```java
    @Autowired private AppUserRepository users;

    public UUID createUser(UUID tenantId, String email) {
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setTenantId(tenantId);
        u.setEmail(email);
        u.setFullName(email);
        u.setPasswordHash("x");
        u.setUserType(UserType.INTERNAL);
        u.setStatus(UserStatus.ACTIVE);
        return users.save(u).getId();
    }
```

Add `AppUserRepository` to the constructor rather than field injection if the existing fixture uses constructor injection; keep it consistent with Task 5.

- [ ] **Step 3: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.RoleServiceTest"`
Expected: FAIL — `RoleService` does not exist.

- [ ] **Step 4: Write the migration**

`backend/src/main/resources/db/migration/V7__roles.sql`:

```sql
CREATE TABLE role (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant(id),
    name             varchar(128) NOT NULL,
    description      text NOT NULL DEFAULT '',
    system_template  boolean NOT NULL DEFAULT false,
    enabled          boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE TABLE role_grant (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    role_id         uuid NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_key  varchar(64) NOT NULL REFERENCES permission(key),
    scope           varchar(16) NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    -- A role grants a given permission at exactly one scope (spec 6.6).
    UNIQUE (role_id, permission_key)
);

CREATE TABLE user_role (
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    user_id    uuid NOT NULL REFERENCES app_user(id),
    role_id    uuid NOT NULL REFERENCES role(id),
    PRIMARY KEY (user_id, role_id)
);

SELECT enable_tenant_rls('role');
SELECT enable_tenant_rls('role_grant');
SELECT enable_tenant_rls('user_role');

GRANT SELECT, INSERT, UPDATE ON role, role_grant, user_role TO onboarding_app;

-- Explicit DELETE (deny-by-default since V2_1). These are authorization
-- metadata, not business records: a role is deletable once no users hold it,
-- updateGrants replaces a role's grants wholesale, and unassigning a role
-- removes its user_role row.
GRANT DELETE ON role, role_grant, user_role TO onboarding_app;
```

- [ ] **Step 5: Implement the entities**

`Role` extends `TenantScopedEntity` with `name`, `description`, `systemTemplate`, `enabled`, and:

```java
    @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<RoleGrant> grants = new HashSet<>();
```

`RoleGrant` extends `TenantScopedEntity` with `@ManyToOne @JoinColumn(name="role_id") Role role`, `@Column(name="permission_key") String permissionKey`, and `@Enumerated(EnumType.STRING) Scope scope`.

`UserRole` maps the `user_role` join table with an `@IdClass` or `@EmbeddedId` of `(userId, roleId)` plus `tenantId`.

- [ ] **Step 6: Implement `InvalidGrantException` and `RoleService`**

```java
package co.ara.onboarding.authz;

public class InvalidGrantException extends RuntimeException {
    public InvalidGrantException(String permissionKey, Scope scope) {
        super("Permission '" + permissionKey + "' does not allow scope " + scope);
    }
    public InvalidGrantException(String permissionKey) {
        super("Unknown permission '" + permissionKey + "'");
    }
}
```

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final AuditRecorder audit;

    public RoleService(RoleRepository roles, UserRoleRepository userRoles, AuditRecorder audit) {
        this.roles = roles;
        this.userRoles = userRoles;
        this.audit = audit;
    }

    @RequirePermission(PermissionKeys.ROLE_MANAGE)
    @Transactional
    public UUID createRole(String name, String description, Map<String, Scope> grants) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setTenantId(TenantContext.getRequired());
        role.setName(name);
        role.setDescription(description == null ? "" : description);
        role.setEnabled(true);
        applyGrants(role, grants);
        roles.save(role);
        audit.record(AuditActions.ROLE_CREATED, "role", role.getId(),
                "Created role " + name, Map.of("grants", grants));
        return role.getId();
    }

    @RequirePermission(PermissionKeys.ROLE_MANAGE)
    @Transactional
    public void updateGrants(UUID roleId, Map<String, Scope> grants) {
        Role role = roles.findById(roleId).orElseThrow();
        role.getGrants().clear();
        applyGrants(role, grants);
        roles.save(role);
        audit.record(AuditActions.ROLE_UPDATED, "role", roleId,
                "Updated grants for role " + role.getName(), Map.of("grants", grants));
    }

    @RequirePermission(PermissionKeys.ROLE_MANAGE)
    @Transactional
    public void setEnabled(UUID roleId, boolean enabled) {
        Role role = roles.findById(roleId).orElseThrow();
        role.setEnabled(enabled);
        roles.save(role);
        if (!enabled) {
            audit.record(AuditActions.ROLE_DISABLED, "role", roleId,
                    "Disabled role " + role.getName(), Map.of());
        }
    }

    @RequirePermission(PermissionKeys.ROLE_MANAGE)
    @Transactional
    public void deleteRole(UUID roleId) {
        if (userRoles.countByRoleId(roleId) > 0) {
            throw new IllegalStateException("Role still has users assigned; disable it instead");
        }
        roles.deleteById(roleId);
    }

    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public void assignRole(UUID userId, UUID roleId) {
        userRoles.save(new UserRole(TenantContext.getRequired(), userId, roleId));
        audit.record(AuditActions.USER_ROLE_ASSIGNED, "app_user", userId,
                "Assigned role", Map.of("roleId", roleId.toString()));
    }

    /** Validates every grant against the catalog before persisting any of them. */
    private void applyGrants(Role role, Map<String, Scope> grants) {
        grants.forEach((key, scope) -> {
            if (PermissionCatalog.byKey(key).isEmpty()) throw new InvalidGrantException(key);
            if (!PermissionCatalog.allows(key, scope)) throw new InvalidGrantException(key, scope);
        });
        grants.forEach((key, scope) -> {
            RoleGrant g = new RoleGrant();
            g.setId(UUID.randomUUID());
            g.setTenantId(role.getTenantId());
            g.setRole(role);
            g.setPermissionKey(key);
            g.setScope(scope);
            role.getGrants().add(g);
        });
    }
}
```

Validation runs over the whole map before any grant is added, so a partially-valid update never leaves a partially-applied role.

- [ ] **Step 7: Map `InvalidGrantException` to 400**

Add to `ApiExceptionHandler`:

```java
    @ExceptionHandler(InvalidGrantException.class)
    ProblemDetail invalidGrant(InvalidGrantException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
```

- [ ] **Step 8: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.RoleServiceTest"`
Expected: PASS — all four tests. The `@RequirePermission` annotation has no behaviour yet (Task 13), so it does not block these tests.

- [ ] **Step 9: Commit**

```bash
git add backend/
git commit -m "feat: add tenant-owned roles with validated per-permission scope"
```

---

### Task 10: Role templates and tenant provisioning

The twelve PRD roles, seeded into every new tenant.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/authz/RoleTemplates.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/TenantProvisioningService.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/PlatformTenantController.java`
- Test: `backend/src/test/java/co/ara/onboarding/tenancy/TenantProvisioningTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/RoleTemplateValidityTest.java`

**Interfaces:**
- Consumes: `RoleService`, `PermissionCatalog`, `TenantRepository`, `AuditRecorder`.
- Produces:
  - `RoleTemplates.all()` → `List<RoleTemplate>` where `record RoleTemplate(String name, String description, Map<String,Scope> grants)`.
  - `TenantProvisioningService.provision(String slug, String name, String adminEmail, String adminFullName)` → `UUID tenantId`. Creates the tenant, seeds roles, creates the first `INTERNAL` administrator user in `INVITED` status.

- [ ] **Step 1: Write the failing template validity test**

This guards the startup check required by spec §6.2.

`backend/src/test/java/co/ara/onboarding/authz/RoleTemplateValidityTest.java`:

```java
package co.ara.onboarding.authz;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RoleTemplateValidityTest {

    @Test
    void everyTemplateGrantUsesAValidPermissionAndScope() {
        RoleTemplates.all().forEach(t ->
            t.grants().forEach((key, scope) -> {
                assertThat(PermissionCatalog.byKey(key))
                        .as("template '%s' references unknown permission '%s'", t.name(), key)
                        .isPresent();
                assertThat(PermissionCatalog.allows(key, scope))
                        .as("template '%s' grants '%s' at disallowed scope %s", t.name(), key, scope)
                        .isTrue();
            }));
    }

    @Test
    void allTwelvePrdRolesArePresent() {
        assertThat(RoleTemplates.all()).hasSize(12);
        assertThat(RoleTemplates.all().stream().map(RoleTemplates.RoleTemplate::name))
            .contains("Sales Representative", "Account Manager", "Project Manager",
                      "Service Provider", "Business Partner", "Operations",
                      "Legal", "Finance", "Technical", "Compliance",
                      "Support", "Administrator");
    }
}
```

- [ ] **Step 2: Write the failing provisioning test**

`backend/src/test/java/co/ara/onboarding/tenancy/TenantProvisioningTest.java`:

```java
package co.ara.onboarding.tenancy;

import co.ara.onboarding.authz.RoleRepository;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class TenantProvisioningTest extends PostgresTestBase {

    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roles;
    @Autowired AppUserRepository users;
    @Autowired TenantFixture fixture;

    @Test
    void provisioningSeedsTwelveRolesAndAnInvitedAdministrator() {
        UUID tenantId = provisioning.provision(
                "newco", "New Co", "admin@newco.example", "New Co Admin");

        fixture.runAs(tenantId, () -> {
            assertThat(roles.findAll()).hasSize(12);
            var admin = users.findByTenantIdAndEmailIgnoreCase(tenantId, "admin@newco.example");
            assertThat(admin).isPresent();
            assertThat(admin.get().getStatus()).isEqualTo(UserStatus.INVITED);
        });
    }
}
```

- [ ] **Step 3: Run both to verify they fail**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.RoleTemplateValidityTest" --tests "co.ara.onboarding.tenancy.TenantProvisioningTest"`
Expected: FAIL — `RoleTemplates` and `TenantProvisioningService` do not exist.

- [ ] **Step 4: Implement `RoleTemplates`**

Scopes below are deliberate starting points, not arbitrary. Operational roles that work case-by-case default to `TEAM`; Administrator is the only role granted `ROLE_MANAGE`.

```java
package co.ara.onboarding.authz;

import java.util.*;
import static co.ara.onboarding.authz.PermissionKeys.*;
import static co.ara.onboarding.authz.Scope.*;

public final class RoleTemplates {

    public record RoleTemplate(String name, String description, Map<String, Scope> grants) {}

    private static final List<RoleTemplate> TEMPLATES = List.of(
        new RoleTemplate("Sales Representative", "Owns prospects and new customers", Map.of(
            CUSTOMER_VIEW, ASSIGNED, CUSTOMER_CREATE, ALL, CUSTOMER_EDIT, ASSIGNED,
            CONTACT_VIEW, ASSIGNED, CONTACT_MANAGE, ASSIGNED, INVITATION_SEND, ASSIGNED)),

        new RoleTemplate("Account Manager", "Owns ongoing customer relationships", Map.of(
            CUSTOMER_VIEW, TEAM, CUSTOMER_EDIT, TEAM, CONTACT_VIEW, TEAM,
            CONTACT_MANAGE, TEAM, INVITATION_SEND, TEAM, USER_VIEW, TEAM)),

        new RoleTemplate("Project Manager", "Coordinates onboarding delivery", Map.of(
            CUSTOMER_VIEW, TEAM, CUSTOMER_EDIT, TEAM, CONTACT_VIEW, TEAM,
            INVITATION_SEND, TEAM, USER_VIEW, TEAM, AUDIT_VIEW, TEAM)),

        new RoleTemplate("Service Provider", "Delivers technical services", Map.of(
            CUSTOMER_VIEW, ASSIGNED, CONTACT_VIEW, ASSIGNED)),

        new RoleTemplate("Business Partner", "External delivery partner", Map.of(
            CUSTOMER_VIEW, ASSIGNED, CONTACT_VIEW, ASSIGNED)),

        new RoleTemplate("Operations", "Runs day-to-day onboarding operations", Map.of(
            CUSTOMER_VIEW, DEPARTMENT, CUSTOMER_EDIT, DEPARTMENT,
            CONTACT_VIEW, DEPARTMENT, USER_VIEW, DEPARTMENT)),

        new RoleTemplate("Legal", "Reviews agreements and legal requirements", Map.of(
            CUSTOMER_VIEW, ALL, CONTACT_VIEW, ALL, AUDIT_VIEW, ALL)),

        new RoleTemplate("Finance", "Handles billing and financial verification", Map.of(
            CUSTOMER_VIEW, ALL, CONTACT_VIEW, ALL)),

        new RoleTemplate("Technical", "Performs technical setup and testing", Map.of(
            CUSTOMER_VIEW, TEAM, CONTACT_VIEW, TEAM)),

        new RoleTemplate("Compliance", "Verifies KYC and regulatory requirements", Map.of(
            CUSTOMER_VIEW, ALL, CONTACT_VIEW, ALL, AUDIT_VIEW, ALL)),

        new RoleTemplate("Support", "Assists customers post-activation", Map.of(
            CUSTOMER_VIEW, TEAM, CONTACT_VIEW, TEAM)),

        new RoleTemplate("Administrator", "Full tenant administration", Map.of(
            TENANT_SETTINGS_VIEW, ALL, TENANT_SETTINGS_EDIT, ALL,
            USER_VIEW, ALL, USER_MANAGE, ALL, ROLE_VIEW, ALL, ROLE_MANAGE, ALL,
            DEPARTMENT_MANAGE, ALL, TEAM_MANAGE, ALL,
            CUSTOMER_VIEW, ALL, CUSTOMER_CREATE, ALL, CUSTOMER_EDIT, ALL,
            CUSTOMER_DEACTIVATE, ALL, CONTACT_VIEW, ALL, CONTACT_MANAGE, ALL,
            INVITATION_SEND, ALL, AUDIT_VIEW, ALL))
    );

    private RoleTemplates() {}

    public static List<RoleTemplate> all() { return TEMPLATES; }
}
```

- [ ] **Step 5: Implement `TenantProvisioningService`**

```java
package co.ara.onboarding.tenancy;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.*;
import co.ara.onboarding.identity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

@Service
public class TenantProvisioningService {

    private final TenantRepository tenants;
    private final RoleRepository roles;
    private final AppUserRepository users;
    private final TenantConnectionCustomizer binder;
    private final AuditRecorder audit;

    public TenantProvisioningService(TenantRepository tenants, RoleRepository roles,
                                     AppUserRepository users,
                                     TenantConnectionCustomizer binder, AuditRecorder audit) {
        this.tenants = tenants;
        this.roles = roles;
        this.users = users;
        this.binder = binder;
        this.audit = audit;
    }

    /**
     * Platform-admin operation: not gated by tenant permissions, because no tenant
     * user exists yet. Access is restricted at the /api/platform/** security rule.
     */
    @Transactional
    public UUID provision(String slug, String name, String adminEmail, String adminFullName) {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setSlug(slug);
        tenant.setName(name);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenants.save(tenant);

        TenantContext.set(tenant.getId());
        binder.bind(tenant.getId());

        for (RoleTemplates.RoleTemplate t : RoleTemplates.all()) {
            Role role = new Role();
            role.setId(UUID.randomUUID());
            role.setTenantId(tenant.getId());
            role.setName(t.name());
            role.setDescription(t.description());
            role.setSystemTemplate(true);
            role.setEnabled(true);
            t.grants().forEach((key, scope) -> {
                if (!PermissionCatalog.allows(key, scope)) {
                    throw new InvalidGrantException(key, scope);
                }
                RoleGrant g = new RoleGrant();
                g.setId(UUID.randomUUID());
                g.setTenantId(tenant.getId());
                g.setRole(role);
                g.setPermissionKey(key);
                g.setScope(scope);
                role.getGrants().add(g);
            });
            roles.save(role);
        }

        AppUser admin = new AppUser();
        admin.setId(UUID.randomUUID());
        admin.setTenantId(tenant.getId());
        admin.setEmail(adminEmail);
        admin.setFullName(adminFullName);
        admin.setUserType(UserType.INTERNAL);
        admin.setStatus(UserStatus.INVITED);
        users.save(admin);

        Role adminRole = roles.findByTenantIdAndName(tenant.getId(), "Administrator").orElseThrow();
        // assignRole is gated; write the join row directly during provisioning.
        roles.assignRoleDirect(tenant.getId(), admin.getId(), adminRole.getId());

        audit.record(AuditActions.TENANT_CREATED, "tenant", tenant.getId(),
                "Provisioned tenant " + slug, Map.of("slug", slug));
        return tenant.getId();
    }
}
```

Add to `RoleRepository`:

```java
    Optional<Role> findByTenantIdAndName(UUID tenantId, String name);

    @Modifying
    @Query(value = "INSERT INTO user_role (tenant_id, user_id, role_id) VALUES (?1, ?2, ?3)",
           nativeQuery = true)
    void assignRoleDirect(UUID tenantId, UUID userId, UUID roleId);
```

- [ ] **Step 6: Add the platform controller**

```java
package co.ara.onboarding.tenancy;

import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform/tenants")
public class PlatformTenantController {

    public record ProvisionRequest(String slug, String name, String adminEmail, String adminFullName) {}

    private final TenantProvisioningService provisioning;

    public PlatformTenantController(TenantProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @PostMapping
    public Map<String, UUID> provision(@RequestBody ProvisionRequest request) {
        UUID id = provisioning.provision(
                request.slug(), request.name(), request.adminEmail(), request.adminFullName());
        return Map.of("tenantId", id);
    }
}
```

- [ ] **Step 7: Run both tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.RoleTemplateValidityTest" --tests "co.ara.onboarding.tenancy.TenantProvisioningTest"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/
git commit -m "feat: seed twelve PRD role templates during tenant provisioning"
```

---

### Task 11: Customer and contact tables

Data only — no service, no endpoints. This lands before the descriptor registry because Task 12's startup check requires a descriptor for every record-scoped permission, and a descriptor needs its entity.

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__customer.sql`
- Create: `backend/src/main/java/co/ara/onboarding/customer/Customer.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerStatus.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerContact.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/ContactStatus.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerRepository.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerContactRepository.java`
- Test: `backend/src/test/java/co/ara/onboarding/customer/CustomerPersistenceTest.java`

**Interfaces:**
- Consumes: `TenantScopedEntity`, `TenantFixture`.
- Produces:
  - `CustomerStatus` — `PROSPECT`, `ACTIVE`, `ON_HOLD`, `INACTIVE` (spec §9.1).
  - `ContactStatus` — `ACTIVE`, `INACTIVE`. Portal-access state is **not** duplicated here; it is read from the linked `AppUser.status`.
  - `Customer` — `legalName`, `displayName`, `status`, `industry`, `country`, `externalRef`, `ownerUserId`, `owningDepartmentId`, `owningTeamId`, `createdBy`.
  - `CustomerContact` — `customerId`, `userId` (nullable until invitation accepted), `fullName`, `email`, `title`, `phone`, `primaryContact`, `status`.
  - `CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer>`.
  - `CustomerContactRepository extends JpaRepository<CustomerContact, UUID>, JpaSpecificationExecutor<CustomerContact>` with `List<CustomerContact> findByCustomerId(UUID)`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/co/ara/onboarding/customer/CustomerPersistenceTest.java`:

```java
package co.ara.onboarding.customer;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerPersistenceTest extends PostgresTestBase {

    @Autowired CustomerRepository customers;
    @Autowired CustomerContactRepository contacts;
    @Autowired TenantFixture fixture;

    @Test
    void persistsCustomerWithOwnershipColumns() {
        UUID tenant = fixture.createTenant("cust-persist");
        UUID owner = fixture.createUser(tenant, "owner@example.com");

        fixture.runAs(tenant, () -> {
            Customer c = new Customer();
            c.setId(UUID.randomUUID());
            c.setTenantId(tenant);
            c.setLegalName("Acme Holdings Ltd");
            c.setDisplayName("Acme");
            c.setStatus(CustomerStatus.PROSPECT);
            c.setOwnerUserId(owner);
            c.setCreatedBy(owner);
            customers.save(c);

            assertThat(customers.findById(c.getId())).isPresent()
                .get().extracting(Customer::getOwnerUserId).isEqualTo(owner);
        });
    }

    @Test
    void contactExistsBeforeAnyUserAccount() {
        UUID tenant = fixture.createTenant("contact-before-user");
        UUID owner = fixture.createUser(tenant, "owner2@example.com");

        fixture.runAs(tenant, () -> {
            Customer c = new Customer();
            c.setId(UUID.randomUUID());
            c.setTenantId(tenant);
            c.setLegalName("Beta Ltd");
            c.setDisplayName("Beta");
            c.setStatus(CustomerStatus.PROSPECT);
            c.setOwnerUserId(owner);
            c.setCreatedBy(owner);
            customers.save(c);

            CustomerContact contact = new CustomerContact();
            contact.setId(UUID.randomUUID());
            contact.setTenantId(tenant);
            contact.setCustomerId(c.getId());
            contact.setFullName("Jordan Reyes");
            contact.setEmail("jordan@beta.example");
            contact.setStatus(ContactStatus.ACTIVE);
            contact.setPrimaryContact(true);
            contacts.save(contact);

            // userId stays null until the invitation is accepted (spec 9.1, QA Q12).
            assertThat(contacts.findByCustomerId(c.getId()))
                    .singleElement()
                    .extracting(CustomerContact::getUserId).isNull();
        });
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.customer.CustomerPersistenceTest"`
Expected: FAIL — customer classes do not exist.

- [ ] **Step 3: Write the migration**

`backend/src/main/resources/db/migration/V8__customer.sql`:

```sql
CREATE TABLE customer (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid NOT NULL REFERENCES tenant(id),
    legal_name            varchar(255) NOT NULL,
    display_name          varchar(255) NOT NULL,
    status                varchar(16)  NOT NULL,
    industry              varchar(128),
    country               varchar(2),
    external_ref          varchar(128),
    owner_user_id         uuid REFERENCES app_user(id),
    owning_department_id  uuid REFERENCES department(id),
    owning_team_id        uuid REFERENCES team(id),
    created_by            uuid REFERENCES app_user(id),
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL
);

CREATE INDEX customer_tenant_status_idx ON customer (tenant_id, status);
CREATE INDEX customer_tenant_owner_idx  ON customer (tenant_id, owner_user_id);
CREATE INDEX customer_tenant_team_idx   ON customer (tenant_id, owning_team_id);

CREATE TABLE customer_contact (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant(id),
    customer_id      uuid NOT NULL REFERENCES customer(id),
    user_id          uuid REFERENCES app_user(id),
    full_name        varchar(255) NOT NULL,
    email            varchar(320) NOT NULL,
    title            varchar(128),
    phone            varchar(64),
    primary_contact  boolean NOT NULL DEFAULT false,
    status           varchar(16) NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    UNIQUE (customer_id, email)
);

CREATE INDEX customer_contact_customer_idx ON customer_contact (tenant_id, customer_id);

SELECT enable_tenant_rls('customer');
SELECT enable_tenant_rls('customer_contact');

GRANT SELECT, INSERT, UPDATE ON customer, customer_contact TO onboarding_app;
-- No DELETE grant, and none is inherited: DELETE is deny-by-default since
-- V2_1. Business records are deactivated, never deleted (spec 9.4).
```

The `REVOKE DELETE` turns "no hard deletes" from a code convention into a database guarantee. Sub-projects 2–9 should follow this pattern for their own business tables.

- [ ] **Step 4: Implement the enums and entities**

```java
package co.ara.onboarding.customer;
public enum CustomerStatus { PROSPECT, ACTIVE, ON_HOLD, INACTIVE }
```

```java
package co.ara.onboarding.customer;
public enum ContactStatus { ACTIVE, INACTIVE }
```

`Customer` extends `TenantScopedEntity` and maps every column from the migration with standard getters and setters, using `@Enumerated(EnumType.STRING)` for `status`. `CustomerContact` does the same; note the field is named `primaryContact` mapping to column `primary_contact`, because `primary` is awkward as a Java identifier and `isPrimary()` reads poorly next to JPA conventions.

- [ ] **Step 5: Implement the repositories**

```java
package co.ara.onboarding.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.UUID;

public interface CustomerRepository
        extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {}
```

```java
package co.ara.onboarding.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.List;
import java.util.UUID;

public interface CustomerContactRepository
        extends JpaRepository<CustomerContact, UUID>, JpaSpecificationExecutor<CustomerContact> {
    List<CustomerContact> findByCustomerId(UUID customerId);
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.customer.CustomerPersistenceTest"`
Expected: PASS

- [ ] **Step 7: Verify the RLS meta-test still passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.architecture.RlsCoverageTest"`
Expected: PASS. If it fails, an `enable_tenant_rls` call is missing from `V8`.

- [ ] **Step 8: Commit**

```bash
git add backend/
git commit -m "feat: add customer and contact tables with no-delete grants"
```

---

### Task 12: Resource descriptors and the registry

Declares, per resource type, how `DEPARTMENT`, `TEAM`, and `ASSIGNED` resolve — and refuses to start if any record-scoped permission lacks a descriptor (spec §6.9).

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/authz/RelationshipType.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/AuthContext.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/ResourceAuthorizationDescriptor.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/DescriptorRegistry.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/AppUserDescriptor.java`
- Create: `backend/src/main/java/co/ara/onboarding/audit/AuditEventDescriptor.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerDescriptor.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerContactDescriptor.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/DescriptorRegistryTest.java`

**Interfaces:**
- Consumes: `PermissionCatalog`, `Customer`, `CustomerContact`, `AppUser`, `AuditEvent`.
- Produces:
  - `RelationshipType` — `OWNER`, `ASSIGNEE`, `PARTICIPANT`, `APPROVER`, `CREATOR` (spec §6.4).
  - `AuthContext` — `record AuthContext(UUID tenantId, UUID userId, UserType userType, UUID departmentId, Set<UUID> teamIds)`.
  - `ResourceAuthorizationDescriptor<T>` — `resourceType()`, `entityType()`, `assignedRelationships()`, `departmentScope(AuthContext)`, `teamScope(AuthContext)`, `assignedScope(AuthContext)`.
  - `DescriptorRegistry.forEntity(Class<T>)`, `.forResourceType(String)`, `.validate()`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/co/ara/onboarding/authz/DescriptorRegistryTest.java`:

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DescriptorRegistryTest extends PostgresTestBase {

    @Autowired DescriptorRegistry registry;

    @Test
    void everyRecordScopedPermissionHasADescriptor() {
        // Passes only because all four descriptors are registered; this is the
        // same assertion the application makes at startup.
        registry.validate();
    }

    @Test
    void missingDescriptorIsAStartupFailure() {
        DescriptorRegistry empty = new DescriptorRegistry(List.of());
        assertThatThrownBy(empty::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no ResourceAuthorizationDescriptor");
    }

    @Test
    void customerDeclaresOwnerOnlyForAssignedScope() {
        var descriptor = registry.forEntity(Customer.class);
        assertThat(descriptor.assignedRelationships())
                .containsExactly(RelationshipType.OWNER);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.DescriptorRegistryTest"`
Expected: FAIL — registry and descriptors do not exist.

- [ ] **Step 3: Implement `RelationshipType` and `AuthContext`**

```java
package co.ara.onboarding.authz;

/**
 * Relationships that can qualify a record for ASSIGNED scope.
 * ASSIGNED always means a PERSONAL relationship — a record assigned to a team
 * the user belongs to is TEAM scope, never ASSIGNED (spec 6.4).
 */
public enum RelationshipType { OWNER, ASSIGNEE, PARTICIPANT, APPROVER, CREATOR }
```

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.identity.UserType;
import java.util.Set;
import java.util.UUID;

public record AuthContext(UUID tenantId, UUID userId, UserType userType,
                          UUID departmentId, Set<UUID> teamIds) {}
```

- [ ] **Step 4: Implement the descriptor interface**

```java
package co.ara.onboarding.authz;

import org.springframework.data.jpa.domain.Specification;
import java.util.Set;

public interface ResourceAuthorizationDescriptor<T> {

    String resourceType();

    Class<T> entityType();

    /** Which personal relationships qualify a record for ASSIGNED scope. */
    Set<RelationshipType> assignedRelationships();

    Specification<T> departmentScope(AuthContext ctx);

    Specification<T> teamScope(AuthContext ctx);

    Specification<T> assignedScope(AuthContext ctx);
}
```

- [ ] **Step 5: Implement the registry with its startup check**

```java
package co.ara.onboarding.authz;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class DescriptorRegistry {

    private final Map<String, ResourceAuthorizationDescriptor<?>> byResourceType = new HashMap<>();
    private final Map<Class<?>, ResourceAuthorizationDescriptor<?>> byEntity = new HashMap<>();

    public DescriptorRegistry(List<ResourceAuthorizationDescriptor<?>> descriptors) {
        for (var d : descriptors) {
            byResourceType.put(d.resourceType(), d);
            byEntity.put(d.entityType(), d);
        }
    }

    /**
     * Fails startup when a permission allows a record-level scope but no descriptor
     * can resolve it. Refusing to boot is deliberate: the alternative is silently
     * allowing access (spec 6.9).
     */
    @PostConstruct
    public void validate() {
        List<String> problems = new ArrayList<>();
        for (Permission p : PermissionCatalog.all()) {
            boolean recordScoped = p.allowedScopes().stream()
                    .anyMatch(s -> s != Scope.ALL);
            if (!recordScoped) continue;
            if (p.resourceType() == null) {
                problems.add("permission '" + p.key() + "' allows record scopes but has no resourceType");
            } else if (!byResourceType.containsKey(p.resourceType())) {
                problems.add("permission '" + p.key() + "' targets resource type '"
                        + p.resourceType() + "' with no ResourceAuthorizationDescriptor");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Authorization configuration is incomplete: " + problems);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> ResourceAuthorizationDescriptor<T> forEntity(Class<T> entityType) {
        var d = byEntity.get(entityType);
        if (d == null) {
            throw new IllegalStateException(
                    "No ResourceAuthorizationDescriptor registered for " + entityType.getName());
        }
        return (ResourceAuthorizationDescriptor<T>) d;
    }

    public ResourceAuthorizationDescriptor<?> forResourceType(String resourceType) {
        var d = byResourceType.get(resourceType);
        if (d == null) {
            throw new IllegalStateException(
                    "No ResourceAuthorizationDescriptor registered for '" + resourceType + "'");
        }
        return d;
    }
}
```

- [ ] **Step 6: Implement the four descriptors**

`customer/CustomerDescriptor.java`:

```java
package co.ara.onboarding.customer;

import co.ara.onboarding.authz.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class CustomerDescriptor implements ResourceAuthorizationDescriptor<Customer> {

    @Override public String resourceType() { return "customer"; }

    @Override public Class<Customer> entityType() { return Customer.class; }

    /** OWNER only in sub-project 1. CREATOR is deliberately excluded (spec 6.4). */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER);
    }

    @Override public Specification<Customer> departmentScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(root.get("owningDepartmentId"), ctx.departmentId());
    }

    @Override public Specification<Customer> teamScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : root.get("owningTeamId").in(ctx.teamIds());
    }

    @Override public Specification<Customer> assignedScope(AuthContext ctx) {
        return (root, query, cb) -> cb.equal(root.get("ownerUserId"), ctx.userId());
    }
}
```

A user with no department returns `cb.disjunction()` — matches nothing. Fail closed, never open.

`customer/CustomerContactDescriptor.java` scopes through the parent customer with a subquery:

```java
package co.ara.onboarding.customer;

import co.ara.onboarding.authz.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

@Component
public class CustomerContactDescriptor implements ResourceAuthorizationDescriptor<CustomerContact> {

    @Override public String resourceType() { return "customer_contact"; }

    @Override public Class<CustomerContact> entityType() { return CustomerContact.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER);
    }

    @Override public Specification<CustomerContact> departmentScope(AuthContext ctx) {
        return viaCustomer((root, query, cb, customer) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(customer.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<CustomerContact> teamScope(AuthContext ctx) {
        return viaCustomer((root, query, cb, customer) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : customer.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<CustomerContact> assignedScope(AuthContext ctx) {
        return viaCustomer((root, query, cb, customer) ->
                cb.equal(customer.get("ownerUserId"), ctx.userId()));
    }

    /** Contacts inherit their scope from the customer they belong to. */
    private Specification<CustomerContact> viaCustomer(CustomerCondition condition) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var customer = subquery.from(Customer.class);
            subquery.select(customer.get("id"))
                    .where(condition.build(root, query, cb, customer));
            return root.get("customerId").in(subquery);
        };
    }

    @FunctionalInterface
    private interface CustomerCondition {
        jakarta.persistence.criteria.Predicate build(
                jakarta.persistence.criteria.Root<CustomerContact> root,
                jakarta.persistence.criteria.CriteriaQuery<?> query,
                jakarta.persistence.criteria.CriteriaBuilder cb,
                jakarta.persistence.criteria.Root<Customer> customer);
    }
}
```

`identity/AppUserDescriptor.java` — `resourceType()` returns `"app_user"`, `assignedRelationships()` returns `Set.of(RelationshipType.OWNER)` meaning the user themselves, `departmentScope` compares `departmentId`, `teamScope` uses an `@ElementCollection` join on `teamIds`, `assignedScope` compares `id` to `ctx.userId()`.

`audit/AuditEventDescriptor.java` — `resourceType()` returns `"audit_event"`. Audit events have no owner, so `assignedRelationships()` returns `Set.of()` and `assignedScope` returns `cb.disjunction()`. `departmentScope` and `teamScope` resolve through `actorUserId` joined to `app_user`. `AUDIT_VIEW` is only granted at `ALL`, `DEPARTMENT`, or `TEAM`, so the empty `ASSIGNED` implementation is unreachable in practice but must still fail closed.

- [ ] **Step 7: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.DescriptorRegistryTest"`
Expected: PASS — all three tests.

- [ ] **Step 8: Prove the startup check catches a missing descriptor**

Temporarily comment out the `@Component` on `CustomerDescriptor`, run any `@SpringBootTest`, and confirm the context fails with "no ResourceAuthorizationDescriptor". Restore it. This is the guard that protects sub-projects 2–9; verify it works now.

- [ ] **Step 9: Commit**

```bash
git add backend/
git commit -m "feat: add resource authorization descriptors with startup validation"
```

---

### Task 13: Effective permissions and the query predicate builder

Resolves a user's authority per request as the union across all enabled roles, and turns that into a JPA `Specification`. This is the mechanism the whole authorization design rests on.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/authz/EffectivePermissions.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/AuthContextProvider.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/AuthorizationService.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/AuthorizationPredicateBuilder.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/EffectivePermissionsTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/PredicateBuilderTest.java`

**Interfaces:**
- Consumes: `DescriptorRegistry`, `AuthContext`, role tables, `AppUserRepository`.
- Produces:
  - `EffectivePermissions` — `scopesFor(String permissionKey)` → `Set<Scope>` (empty when not granted), `has(String permissionKey)` → boolean.
  - `AuthContextProvider.current()` → `AuthContext` for the authenticated user; throws `AccessDeniedException` when unauthenticated.
  - `AuthorizationService.effectivePermissions()` — resolved per request, memoized for the request only (spec §6.7).
  - `AuthorizationPredicateBuilder.forPermission(String permissionKey, Class<T> entityType)` → `Specification<T>`.

- [ ] **Step 1: Write the failing effective-permissions test**

`backend/src/test/java/co/ara/onboarding/authz/EffectivePermissionsTest.java`:

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class EffectivePermissionsTest extends PostgresTestBase {

    @Autowired AuthorizationService authorization;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;

    @Test
    void multipleRolesUnionTheirScopes() {
        UUID tenant = fixture.createTenant("union-test");
        UUID user = fixture.createUser(tenant, "multi@example.com");

        fixture.runAs(tenant, () -> {
            UUID teamRole = roles.createRole("Team Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            UUID assignedRole = roles.createRole("Own Records", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user, teamRole);
            roles.assignRole(user, assignedRole);
        });

        fixture.runAsUser(tenant, user, () -> {
            var effective = authorization.effectivePermissions();
            assertThat(effective.scopesFor(PermissionKeys.CUSTOMER_VIEW))
                    .containsExactlyInAnyOrder(Scope.TEAM, Scope.ASSIGNED);
        });
    }

    @Test
    void disabledRoleContributesNothing() {
        UUID tenant = fixture.createTenant("disabled-role");
        UUID user = fixture.createUser(tenant, "disabled@example.com");

        UUID[] roleId = new UUID[1];
        fixture.runAs(tenant, () -> {
            roleId[0] = roles.createRole("Temp", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            roles.assignRole(user, roleId[0]);
        });

        fixture.runAsUser(tenant, user, () ->
            assertThat(authorization.effectivePermissions()
                    .has(PermissionKeys.CUSTOMER_VIEW)).isTrue());

        fixture.runAs(tenant, () -> roles.setEnabled(roleId[0], false));

        fixture.runAsUser(tenant, user, () ->
            assertThat(authorization.effectivePermissions()
                    .has(PermissionKeys.CUSTOMER_VIEW))
                .as("a disabled role must contribute nothing immediately")
                .isFalse());
    }

    @Test
    void revokedGrantTakesEffectOnTheNextRequest() {
        UUID tenant = fixture.createTenant("revoke-test");
        UUID user = fixture.createUser(tenant, "revoked@example.com");

        UUID[] roleId = new UUID[1];
        fixture.runAs(tenant, () -> {
            roleId[0] = roles.createRole("Editor", "",
                    Map.of(PermissionKeys.CUSTOMER_EDIT, Scope.ALL));
            roles.assignRole(user, roleId[0]);
        });

        fixture.runAsUser(tenant, user, () ->
            assertThat(authorization.effectivePermissions()
                    .has(PermissionKeys.CUSTOMER_EDIT)).isTrue());

        fixture.runAs(tenant, () -> roles.updateGrants(roleId[0], Map.of()));

        fixture.runAsUser(tenant, user, () ->
            assertThat(authorization.effectivePermissions()
                    .has(PermissionKeys.CUSTOMER_EDIT))
                .as("no cross-request permission cache (spec 6.7)")
                .isFalse());
    }

    @Test
    void ungrantedPermissionYieldsEmptyScopeSet() {
        UUID tenant = fixture.createTenant("no-grant");
        UUID user = fixture.createUser(tenant, "nothing@example.com");

        fixture.runAsUser(tenant, user, () ->
            assertThat(authorization.effectivePermissions()
                    .scopesFor(PermissionKeys.CUSTOMER_VIEW)).isEmpty());
    }
}
```

- [ ] **Step 2: Extend `TenantFixture` with `runAsUser`**

Each `runAsUser` call simulates a distinct request, which is what makes the "next request" assertions meaningful.

```java
    /** Runs the action as an authenticated user in a fresh request scope. */
    public void runAsUser(UUID tenantId, UUID userId, Runnable action) {
        // A request scope must exist because AuthorizationService and
        // RequestAuditContext are @RequestScope beans; without it, resolving
        // their scoped proxies throws IllegalStateException.
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        var auth = new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(tenantId, userId), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            runAs(tenantId, action);
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
        }
    }
```

with these imports on `TenantFixture`:

```java
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
```

`RequestContextHolder` is a static utility — do not try to instantiate it. `MockHttpServletRequest` comes from `spring-boot-starter-test`, already on the test classpath from Task 1.

Note the interaction with Task 14: once the permission gate is live, `runAs` itself must run privileged, so `runAs` delegates to `runAsUser` with a tenant administrator. Both methods therefore establish request scope, and nesting them is safe because `setRequestAttributes` is idempotent for this purpose.

- [ ] **Step 3: Define the authenticated principal**

`backend/src/main/java/co/ara/onboarding/authz/AuthenticatedPrincipal.java`:

```java
package co.ara.onboarding.authz;

import java.util.UUID;

/** The identity carried by an authenticated request. Deliberately holds no permissions. */
public record AuthenticatedPrincipal(UUID tenantId, UUID userId) {}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.EffectivePermissionsTest"`
Expected: FAIL — `AuthorizationService` does not exist.

- [ ] **Step 5: Implement `EffectivePermissions`**

```java
package co.ara.onboarding.authz;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public record EffectivePermissions(Map<String, Set<Scope>> byPermission) {

    public Set<Scope> scopesFor(String permissionKey) {
        return byPermission.getOrDefault(permissionKey, Collections.emptySet());
    }

    public boolean has(String permissionKey) {
        return !scopesFor(permissionKey).isEmpty();
    }
}
```

- [ ] **Step 6: Implement `AuthContextProvider`**

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class AuthContextProvider {

    private final AppUserRepository users;

    public AuthContextProvider(AppUserRepository users) { this.users = users; }

    public AuthenticatedPrincipal principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedPrincipal p)) {
            throw new AccessDeniedException("Not authenticated");
        }
        return p;
    }

    public AuthContext current() {
        AuthenticatedPrincipal p = principal();
        AppUser user = users.findById(p.userId())
                .orElseThrow(() -> new AccessDeniedException("Unknown user"));
        return new AuthContext(user.getTenantId(), user.getId(), user.getUserType(),
                user.getDepartmentId(), Set.copyOf(user.getTeamIds()));
    }
}
```

- [ ] **Step 7: Implement `AuthorizationService`**

```java
package co.ara.onboarding.authz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import java.util.*;

/**
 * Request-scoped: effective permissions are resolved once per request and never
 * cached across requests, so a role change takes effect on the next request
 * with no stale authority (spec 6.7).
 */
@Component
@RequestScope
public class AuthorizationService {

    private final JdbcTemplate jdbc;
    private final AuthContextProvider contextProvider;
    private EffectivePermissions memo;

    public AuthorizationService(JdbcTemplate jdbc, AuthContextProvider contextProvider) {
        this.jdbc = jdbc;
        this.contextProvider = contextProvider;
    }

    public EffectivePermissions effectivePermissions() {
        if (memo != null) return memo;

        UUID userId = contextProvider.principal().userId();
        Map<String, Set<Scope>> byPermission = new HashMap<>();

        jdbc.query("""
            SELECT rg.permission_key AS k, rg.scope AS s
            FROM user_role ur
            JOIN role r ON r.id = ur.role_id AND r.enabled = true
            JOIN role_grant rg ON rg.role_id = r.id
            WHERE ur.user_id = ?
            """,
            rs -> {
                String key = rs.getString("k");
                Scope scope = Scope.valueOf(rs.getString("s"));
                // Union across roles. There are no deny grants, so this only
                // ever widens — no precedence order to get wrong (spec 6.5).
                byPermission.computeIfAbsent(key, k -> EnumSet.noneOf(Scope.class)).add(scope);
            },
            userId);

        memo = new EffectivePermissions(Map.copyOf(byPermission));
        return memo;
    }

    public boolean has(String permissionKey) {
        return effectivePermissions().has(permissionKey);
    }
}
```

- [ ] **Step 8: Run the effective-permissions test to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.EffectivePermissionsTest"`
Expected: PASS — all four tests.

- [ ] **Step 9: Write the failing predicate-builder test**

`backend/src/test/java/co/ara/onboarding/authz/PredicateBuilderTest.java`:

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.customer.*;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PredicateBuilderTest extends PostgresTestBase {

    @Autowired AuthorizationPredicateBuilder predicates;
    @Autowired CustomerRepository customers;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;

    @Test
    void assignedScopeSeesPersonallyOwnedRecordOutsideOwnTeam() {
        UUID tenant = fixture.createTenant("assigned-scope");
        UUID user = fixture.createUser(tenant, "owner@example.com");
        UUID otherTeam = fixture.createTeam(tenant, "Other Team");

        fixture.runAs(tenant, () -> {
            // Owned by this user, but belonging to a team the user is NOT in.
            fixture.createCustomer(tenant, "Mine", user, null, otherTeam);
            fixture.createCustomer(tenant, "Not Mine", null, null, otherTeam);

            UUID role = roles.createRole("Assigned Only", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user, role);
        });

        fixture.runAsUser(tenant, user, () -> {
            var spec = predicates.forPermission(PermissionKeys.CUSTOMER_VIEW, Customer.class);
            assertThat(customers.findAll(spec))
                    .extracting(Customer::getDisplayName)
                    .containsExactly("Mine");
        });
    }

    @Test
    void noGrantMatchesNothing() {
        UUID tenant = fixture.createTenant("no-grant-predicate");
        UUID user = fixture.createUser(tenant, "nobody@example.com");

        fixture.runAs(tenant, () -> fixture.createCustomer(tenant, "Hidden", null, null, null));

        fixture.runAsUser(tenant, user, () -> {
            var spec = predicates.forPermission(PermissionKeys.CUSTOMER_VIEW, Customer.class);
            assertThat(customers.findAll(spec))
                    .as("absence of a grant is the denial (spec 6.5)")
                    .isEmpty();
        });
    }

    @Test
    void allScopeMatchesEverythingInTenant() {
        UUID tenant = fixture.createTenant("all-scope");
        UUID user = fixture.createUser(tenant, "admin@example.com");

        fixture.runAs(tenant, () -> {
            fixture.createCustomer(tenant, "One", null, null, null);
            fixture.createCustomer(tenant, "Two", null, null, null);
            UUID role = roles.createRole("All Access", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            roles.assignRole(user, role);
        });

        fixture.runAsUser(tenant, user, () -> {
            var spec = predicates.forPermission(PermissionKeys.CUSTOMER_VIEW, Customer.class);
            assertThat(customers.findAll(spec)).hasSize(2);
        });
    }
}
```

Add `createTeam(UUID tenantId, String name)` and `createCustomer(UUID tenantId, String displayName, UUID ownerUserId, UUID departmentId, UUID teamId)` to `TenantFixture`, following the shape of `createUser` from Task 9.

- [ ] **Step 10: Implement `AuthorizationPredicateBuilder`**

```java
package co.ara.onboarding.authz;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class AuthorizationPredicateBuilder {

    private final AuthorizationService authorization;
    private final AuthContextProvider contextProvider;
    private final DescriptorRegistry registry;

    public AuthorizationPredicateBuilder(AuthorizationService authorization,
                                         AuthContextProvider contextProvider,
                                         DescriptorRegistry registry) {
        this.authorization = authorization;
        this.contextProvider = contextProvider;
        this.registry = registry;
    }

    public <T> Specification<T> forPermission(String permissionKey, Class<T> entityType) {
        Set<Scope> scopes = authorization.effectivePermissions().scopesFor(permissionKey);

        // Fail closed: no grant means no rows, never all rows.
        if (scopes.isEmpty()) return (root, query, cb) -> cb.disjunction();

        // ALL subsumes the others; short-circuit to an unconditional match.
        if (scopes.contains(Scope.ALL)) return (root, query, cb) -> cb.conjunction();

        AuthContext ctx = contextProvider.current();
        ResourceAuthorizationDescriptor<T> descriptor = registry.forEntity(entityType);

        Specification<T> combined = null;
        for (Scope scope : scopes) {
            Specification<T> part = switch (scope) {
                case DEPARTMENT -> descriptor.departmentScope(ctx);
                case TEAM       -> descriptor.teamScope(ctx);
                case ASSIGNED   -> descriptor.assignedScope(ctx);
                case ALL        -> null;   // unreachable, handled above
            };
            if (part == null) continue;
            // Scopes are SETS, not a hierarchy: union them (spec 6.3).
            combined = (combined == null) ? part : combined.or(part);
        }
        return combined == null ? (root, query, cb) -> cb.disjunction() : combined;
    }
}
```

- [ ] **Step 11: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.PredicateBuilderTest"`
Expected: PASS. The first test is the important one — it proves scopes union rather than nest.

- [ ] **Step 12: Commit**

```bash
git add backend/
git commit -m "feat: resolve effective permissions per request and build scope predicates"
```

---

### Task 14: Permission gate and authorized queries

Makes `@RequirePermission` enforce, and provides the only sanctioned way to read tenant data — with an ArchUnit rule that stops anyone bypassing it.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/authz/PermissionGateAspect.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/AuthorizedQuery.java`
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/PermissionGateTest.java`

**Interfaces:**
- Consumes: `AuthorizationService`, `AuthorizationPredicateBuilder`.
- Produces:
  - `PermissionGateAspect` — intercepts `@RequirePermission`, throws `AccessDeniedException` when the user holds the permission at no scope.
  - `AuthorizedQuery.findAll(repo, entityType, permissionKey, extra, pageable)` → `Page<T>`.
  - `AuthorizedQuery.getById(repo, entityType, permissionKey, id)` → `T`, throwing `NoSuchElementException` (→ 404) when out of scope.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/co/ara/onboarding/authz/PermissionGateTest.java`:

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionGateTest extends PostgresTestBase {

    @Autowired RoleService roles;
    @Autowired AuthorizedQuery authorizedQuery;
    @Autowired CustomerRepository customers;
    @Autowired TenantFixture fixture;

    @Test
    void gateRejectsUserWithoutThePermission() {
        UUID tenant = fixture.createTenant("gate-deny");
        UUID user = fixture.createUser(tenant, "ungranted@example.com");

        fixture.runAsUser(tenant, user, () ->
            assertThatThrownBy(() -> roles.createRole("Nope", "", Map.of()))
                .isInstanceOf(AccessDeniedException.class));
    }

    @Test
    void outOfScopeRecordIsNotFoundRatherThanForbidden() {
        UUID tenant = fixture.createTenant("scope-404");
        UUID user = fixture.createUser(tenant, "scoped@example.com");
        UUID otherUser = fixture.createUser(tenant, "other@example.com");

        UUID[] hidden = new UUID[1];
        fixture.runAs(tenant, () -> {
            hidden[0] = fixture.createCustomer(tenant, "Someone Else's", otherUser, null, null);
            UUID role = roles.createRole("Assigned Only", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user, role);
        });

        fixture.runAsUser(tenant, user, () ->
            assertThatThrownBy(() -> authorizedQuery.getById(
                    customers, Customer.class, PermissionKeys.CUSTOMER_VIEW, hidden[0]))
                .as("must not reveal that the record exists (spec 6.8)")
                .isInstanceOf(NoSuchElementException.class));
    }

    @Test
    void authorizedListReturnsOnlyInScopeRecords() {
        UUID tenant = fixture.createTenant("scoped-list");
        UUID user = fixture.createUser(tenant, "lister@example.com");

        fixture.runAs(tenant, () -> {
            fixture.createCustomer(tenant, "Mine", user, null, null);
            fixture.createCustomer(tenant, "Theirs", null, null, null);
            UUID role = roles.createRole("Assigned Only", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user, role);
        });

        fixture.runAsUser(tenant, user, () ->
            assertThat(authorizedQuery.findAll(customers, Customer.class,
                    PermissionKeys.CUSTOMER_VIEW, null, Pageable.unpaged()))
                .extracting(Customer::getDisplayName)
                .containsExactly("Mine"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.PermissionGateTest"`
Expected: FAIL — `AuthorizedQuery` does not exist and `@RequirePermission` has no behaviour.

- [ ] **Step 3: Implement the gate aspect**

```java
package co.ara.onboarding.authz;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.aspectj.lang.JoinPoint;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PermissionGateAspect {

    private final AuthorizationService authorization;

    public PermissionGateAspect(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @Before("@annotation(co.ara.onboarding.authz.RequirePermission)")
    public void enforce(JoinPoint joinPoint) {
        var signature = (MethodSignature) joinPoint.getSignature();
        var annotation = signature.getMethod().getAnnotation(RequirePermission.class);
        if (!authorization.has(annotation.value())) {
            // Deliberately no permission name in the message: do not teach a
            // caller which permission would unlock the endpoint.
            throw new AccessDeniedException("Forbidden");
        }
    }
}
```

The gate answers only "does the user hold this permission at *any* scope". Which records they may touch is the predicate builder's job — the two are separate on purpose.

- [ ] **Step 4: Implement `AuthorizedQuery`**

```java
package co.ara.onboarding.authz;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Component;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The only sanctioned way to read tenant-owned records. Every read is bound to a
 * permission key, so an endpoint cannot issue an unscoped query (spec 6.8).
 */
@Component
public class AuthorizedQuery {

    private final AuthorizationPredicateBuilder predicates;

    public AuthorizedQuery(AuthorizationPredicateBuilder predicates) {
        this.predicates = predicates;
    }

    public <T> Page<T> findAll(JpaSpecificationExecutor<T> repository, Class<T> entityType,
                               String permissionKey, Specification<T> extra, Pageable pageable) {
        Specification<T> authorized = predicates.forPermission(permissionKey, entityType);
        Specification<T> combined = (extra == null) ? authorized : authorized.and(extra);
        return repository.findAll(combined, pageable);
    }

    public <T> T getById(JpaSpecificationExecutor<T> repository, Class<T> entityType,
                         String permissionKey, UUID id) {
        Specification<T> authorized = predicates.forPermission(permissionKey, entityType);
        Specification<T> byId = (root, query, cb) -> cb.equal(root.get("id"), id);
        return repository.findOne(authorized.and(byId))
                // NoSuchElementException maps to 404, never 403 (spec 6.8).
                .orElseThrow(() -> new NoSuchElementException("Not found"));
    }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.authz.PermissionGateTest"`
Expected: PASS — all three tests.

- [ ] **Step 6: Add the ArchUnit rule that forbids bypassing `AuthorizedQuery`**

Add to `AuthorizationCoverageTest`:

```java
    /**
     * Services must read tenant business data through AuthorizedQuery. Calling
     * repository finders directly would skip the scope predicate entirely.
     */
    @ArchTest
    static final ArchRule servicesDoNotCallRepositoryFindersDirectly =
            noClasses().that().resideInAPackage("co.ara.onboarding.customer..")
                .and().haveSimpleNameEndingWith("Service")
                .should().callMethodWhere(
                        target(name("findAll"))
                        .or(target(name("findOne")))
                        .or(target(name("findById")))
                        .or(target(nameStartingWith("findBy"))))
                .because("reads must go through AuthorizedQuery so scope cannot be bypassed");
```

These are the exact static imports it needs — get them wrong and the failure is a confusing compile error rather than an obvious one:

```java
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
```

The `findBy*` clause matters as much as the rest: a derived query like `contactRepository.findByCustomerId(id)` carries no scope predicate either, so it bypasses authorization exactly as `findAll()` would. `CustomerContactService` must therefore reach contacts through `AuthorizedQuery` with a `customerId` specification rather than through a derived finder.

Scope the rule to `co.ara.onboarding.customer..` for now; each later sub-project adds its own domain package here. `TenantProvisioningService` and `RoleService` are outside that package and unaffected — they operate on authorization metadata, not scoped business records.

- [ ] **Step 7: Repair the earlier tests this task deliberately breaks**

Activating the gate breaks every earlier test that called a gated service through `fixture.runAs`, which establishes a tenant but no authenticated user. That fallout is expected — those tests were passing only because the annotation had no behaviour. Fix the fixture, not the services.

Give `TenantFixture` a privileged variant, and change `runAs` to use it:

```java
    /**
     * Runs with a tenant bound AND an authenticated administrator, so gated
     * services work in setup code. Tests asserting authorization behaviour must
     * use runAsUser with a specific user instead.
     */
    public void runAs(UUID tenantId, Runnable action) {
        UUID admin = administratorFor(tenantId);
        runAsUser(tenantId, admin, action);
    }

    /** Lazily creates (once per tenant) an ACTIVE user holding the seeded Administrator role. */
    private UUID administratorFor(UUID tenantId) { /* memoize per tenantId */ }
```

`runAsUser` must also establish request scope, because `AuthorizationService` is `@RequestScope`; it already does this via `RequestContextHolder` from Task 13 Step 2.

Two consequences to handle:
- `TenantProvisioningService.provision` runs before any user exists, so it must not be gated. Keep it ungated and add the documented ArchUnit exclusion from Task 7 Step 5.
- Tests in Tasks 9, 10, 13 that used `runAs` for setup now run as an administrator, which is correct: setup should be privileged, and only the assertions should be scope-constrained.

- [ ] **Step 8: Run the full suite**

Run: `cd backend && ./gradlew test`
Expected: PASS. Every previously green test must be green again. If a test now needs a permission it did not have, change the fixture or the test's role setup — never the gate.

- [ ] **Step 9: Commit**

```bash
git add backend/
git commit -m "feat: enforce permission gate and route all reads through AuthorizedQuery"
```

---

### Task 15: Password hashing and login

Argon2id hashing and a login endpoint issuing a 15-minute access token that carries identity but no permissions.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/auth/PasswordEncoderConfig.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/TokenService.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/JwtAuthenticationFilter.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/AuthController.java`
- Modify: `backend/src/main/java/co/ara/onboarding/platform/SecurityConfig.java`
- Test: `backend/src/test/java/co/ara/onboarding/auth/LoginTest.java`

**Interfaces:**
- Consumes: `AppUserRepository`, `TenantContext`, `AuditRecorder`, `AuthenticatedPrincipal`.
- Produces:
  - `TokenService.issueAccessToken(AppUser)` → `String`; `TokenService.parse(String)` → `Optional<AuthenticatedPrincipal>`.
  - `POST /api/t/{slug}/auth/login` — body `{email, password}`, returns `{accessToken, expiresInSeconds, user:{id, fullName, userType}}`.
  - `JwtAuthenticationFilter` — populates the `SecurityContext` from the `Authorization: Bearer` header.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/co/ara/onboarding/auth/LoginTest.java`:

```java
package co.ara.onboarding.auth;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class LoginTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired TenantFixture fixture;

    @Test
    void loginReturnsAccessTokenForValidCredentials() throws Exception {
        UUID tenant = fixture.createTenant("login-co");
        fixture.createUserWithPassword(tenant, "user@login.example", "correct-horse-battery");

        String body = mvc.perform(post("/api/t/login-co/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                            Map.of("email", "user@login.example",
                                   "password", "correct-horse-battery"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = json.readTree(body).get("accessToken").asText();
        assertThat(tokens.parse(token)).isPresent();
    }

    @Test
    void accessTokenCarriesNoPermissionClaims() throws Exception {
        UUID tenant = fixture.createTenant("no-perm-claims");
        var user = fixture.createUserWithPassword(tenant, "np@example.com", "a-long-password");

        String token = tokens.issueAccessToken(user);
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]));

        assertThat(payload)
            .as("permissions must be resolved server-side, never carried in the token (spec 7.2)")
            .doesNotContain("permission").doesNotContain("scope").doesNotContain("role");
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        UUID tenant = fixture.createTenant("wrong-pw");
        fixture.createUserWithPassword(tenant, "wp@example.com", "the-right-password");

        mvc.perform(post("/api/t/wrong-pw/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                            Map.of("email", "wp@example.com", "password", "the-wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonActiveUserCannotLogIn() throws Exception {
        UUID tenant = fixture.createTenant("invited-user");
        fixture.createInvitedUser(tenant, "pending@example.com", "some-password");

        mvc.perform(post("/api/t/invited-user/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                            Map.of("email", "pending@example.com", "password", "some-password"))))
                .andExpect(status().isUnauthorized());
    }
}
```

Add `createUserWithPassword(UUID, String, String)` returning `AppUser` and `createInvitedUser(UUID, String, String)` to `TenantFixture`, both encoding the password with the injected `PasswordEncoder`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.auth.LoginTest"`
Expected: FAIL — no auth classes.

- [ ] **Step 3: Configure Argon2id**

```java
package co.ara.onboarding.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    /** saltLength, hashLength, parallelism, memoryKb, iterations */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 1 << 16, 3);
    }
}
```

- [ ] **Step 4: Implement `TokenService`**

```java
package co.ara.onboarding.auth;

import co.ara.onboarding.authz.AuthenticatedPrincipal;
import co.ara.onboarding.identity.AppUser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
public class TokenService {

    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;

    public TokenService(@Value("${app.jwt.secret}") String secret,
                        @Value("${app.jwt.issuer}") String issuer,
                        @Value("${app.jwt.access-token-ttl}") Duration ttl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.ttl = ttl;
    }

    /** Identity claims only. Authority is resolved per request (spec 7.2). */
    public String issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .claim("tid", user.getTenantId().toString())
                .claim("typ", user.getUserType().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public Optional<AuthenticatedPrincipal> parse(String token) {
        try {
            var claims = Jwts.parser().verifyWith(key).requireIssuer(issuer)
                    .build().parseSignedClaims(token).getPayload();
            return Optional.of(new AuthenticatedPrincipal(
                    UUID.fromString(claims.get("tid", String.class)),
                    UUID.fromString(claims.getSubject())));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public long ttlSeconds() { return ttl.toSeconds(); }
}
```

- [ ] **Step 5: Implement the authentication filter**

```java
package co.ara.onboarding.auth;

import co.ara.onboarding.platform.RequestAuditContext;
import co.ara.onboarding.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Component
@Order(20)   // after TenantContextFilter
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokens;
    private final ObjectProvider<RequestAuditContext> auditContext;

    public JwtAuthenticationFilter(TokenService tokens,
                                   ObjectProvider<RequestAuditContext> auditContext) {
        this.tokens = tokens;
        this.auditContext = auditContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            tokens.parse(header.substring(7)).ifPresent(principal -> {
                // A token is only valid for the tenant it was issued for.
                // Without this check a valid token would work against any tenant path.
                if (principal.tenantId().equals(TenantContext.getOrNull())) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
                    auditContext.getObject().setActor(
                            principal.userId(), RequestAuditContext.ActorType.USER);
                }
            });
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
```

The tenant match is essential. Test 1 of the negative suite in Task 21 depends on it.

- [ ] **Step 6: Implement `AuthController` login**

```java
package co.ara.onboarding.auth;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.identity.*;
import co.ara.onboarding.tenancy.TenantContext;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/t/{tenantSlug}/auth")
public class AuthController {

    public record LoginRequest(@Email String email, @NotBlank String password) {}
    public record LoginResponse(String accessToken, long expiresInSeconds, Map<String, Object> user) {}

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuditRecorder audit;

    public AuthController(AppUserRepository users, PasswordEncoder passwords,
                          TokenService tokens, AuditRecorder audit) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
    }

    @PostMapping("/login")
    @Transactional
    public LoginResponse login(@RequestBody LoginRequest request) {
        var user = users.findByTenantIdAndEmailIgnoreCase(
                TenantContext.getRequired(), request.email()).orElse(null);

        boolean valid = user != null
                && user.getStatus() == UserStatus.ACTIVE
                && user.getPasswordHash() != null
                && passwords.matches(request.password(), user.getPasswordHash());

        if (!valid) {
            audit.record(AuditActions.LOGIN_FAILED, "app_user",
                    user == null ? null : user.getId(),
                    "Failed login for " + request.email(), Map.of());
            // Identical response whether the user is absent, inactive, or the
            // password is wrong — do not leak which accounts exist.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        user.setLastLoginAt(Instant.now());
        users.save(user);
        audit.record(AuditActions.LOGIN_SUCCEEDED, "app_user", user.getId(),
                "Successful login", Map.of());

        return new LoginResponse(tokens.issueAccessToken(user), tokens.ttlSeconds(),
                Map.of("id", user.getId(), "fullName", user.getFullName(),
                       "userType", user.getUserType().name()));
    }
}
```

- [ ] **Step 7: Tighten `SecurityConfig`**

Replace the `permitAll` rule from Task 4:

```java
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/t/*/auth/login",
                                 "/api/t/*/auth/refresh",
                                 "/api/t/*/auth/activate",
                                 "/api/t/*/auth/password-reset/**").permitAll()
                .requestMatchers("/api/platform/**").permitAll()   // secured in Task 21
                .anyRequest().authenticated())
```

- [ ] **Step 8: Add the reserved MFA challenge step**

Spec §7.8 requires the login flow to reserve a place for MFA without implementing it. Add to `AuthController.login`, immediately after credential verification:

```java
        // Reserved MFA challenge (spec 7.8). TOTP verification lands in a later
        // sub-project; the branch exists now so adding it is not a flow redesign.
        if (user.isMfaEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "MFA is not yet supported");
        }
```

Failing closed is deliberate: an account flagged for MFA must not be able to sign in with a password alone just because the second factor is unimplemented.

Add a test asserting an `mfaEnabled` user receives 501 rather than a token.

- [ ] **Step 9: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.auth.LoginTest"`
Expected: PASS — all five tests.

- [ ] **Step 10: Commit**

```bash
git add backend/
git commit -m "feat: add Argon2id password hashing and JWT login"
```

---

### Task 16: Refresh token rotation and reuse detection

The mechanism that provides breach *detection* rather than only breach resistance (spec §7.4).

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__refresh_token.sql`
- Create: `backend/src/main/java/co/ara/onboarding/auth/RefreshToken.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/RefreshTokenRepository.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/RefreshTokenService.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/RefreshTokenReuseException.java`
- Modify: `backend/src/main/java/co/ara/onboarding/auth/AuthController.java`
- Modify: `backend/src/main/java/co/ara/onboarding/platform/ApiExceptionHandler.java`
- Test: `backend/src/test/java/co/ara/onboarding/auth/RefreshTokenTest.java`

**Interfaces:**
- Consumes: `TokenService`, `AppUserRepository`, `AuditRecorder`.
- Produces:
  - `RefreshTokenService.issue(AppUser, String ip, String userAgent)` → raw token string (returned once, stored only as a SHA-256 hash).
  - `RefreshTokenService.rotate(String rawToken)` → `RotationResult` record `(AppUser user, String newRawToken)`; throws `RefreshTokenReuseException` on reuse.
  - `RefreshTokenService.revokeFamily(UUID familyId)`.
  - `POST /api/t/{slug}/auth/refresh` and `POST /api/t/{slug}/auth/logout`.
  - Cookie: name `refresh_token`, `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/t/{slug}/auth`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/co/ara/onboarding/auth/RefreshTokenTest.java`:

```java
package co.ara.onboarding.auth;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenTest extends PostgresTestBase {

    @Autowired RefreshTokenService refreshTokens;
    @Autowired RefreshTokenRepository repository;
    @Autowired TenantFixture fixture;

    @Test
    void rotationIssuesANewTokenAndRetiresTheOld() {
        UUID tenant = fixture.createTenant("rotate-co");
        var user = fixture.createUserWithPassword(tenant, "rot@example.com", "password-value");

        fixture.runAs(tenant, () -> {
            String first = refreshTokens.issue(user, "127.0.0.1", "test-agent");
            var result = refreshTokens.rotate(first);

            assertThat(result.newRawToken()).isNotEqualTo(first);
            assertThat(result.user().getId()).isEqualTo(user.getId());
        });
    }

    @Test
    void reusingARetiredTokenRevokesTheWholeFamily() {
        UUID tenant = fixture.createTenant("reuse-co");
        var user = fixture.createUserWithPassword(tenant, "reuse@example.com", "password-value");

        fixture.runAs(tenant, () -> {
            String first = refreshTokens.issue(user, "127.0.0.1", "test-agent");
            var second = refreshTokens.rotate(first);

            // Replaying the already-used token means it was stolen.
            assertThatThrownBy(() -> refreshTokens.rotate(first))
                    .isInstanceOf(RefreshTokenReuseException.class);

            // The token issued to the legitimate client is killed too.
            assertThatThrownBy(() -> refreshTokens.rotate(second.newRawToken()))
                    .as("the entire family must be revoked (spec 7.4)")
                    .isInstanceOf(RefreshTokenReuseException.class);
        });
    }

    @Test
    void rawTokenIsNeverStored() {
        UUID tenant = fixture.createTenant("hash-co");
        var user = fixture.createUserWithPassword(tenant, "hash@example.com", "password-value");

        fixture.runAs(tenant, () -> {
            String raw = refreshTokens.issue(user, "127.0.0.1", "test-agent");
            assertThat(repository.findAll())
                    .extracting(RefreshToken::getTokenHash)
                    .noneMatch(stored -> stored.equals(raw));
        });
    }

    @Test
    void expiredTokenIsRejected() {
        UUID tenant = fixture.createTenant("expiry-co");
        var user = fixture.createUserWithPassword(tenant, "exp@example.com", "password-value");

        fixture.runAs(tenant, () -> {
            String raw = refreshTokens.issue(user, "127.0.0.1", "test-agent");
            repository.findAll().forEach(t -> {
                t.setExpiresAt(java.time.Instant.now().minusSeconds(60));
                repository.save(t);
            });
            assertThatThrownBy(() -> refreshTokens.rotate(raw))
                    .isInstanceOf(RefreshTokenReuseException.class);
        });
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.auth.RefreshTokenTest"`
Expected: FAIL — refresh token classes do not exist.

- [ ] **Step 3: Write the migration**

`backend/src/main/resources/db/migration/V9__refresh_token.sql`:

```sql
CREATE TABLE refresh_token (
    id          uuid PRIMARY KEY,
    -- Denormalized from the owning user so this table carries RLS like every
    -- other tenant-owned table rather than relying on a join (spec 7.3).
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    user_id     uuid NOT NULL REFERENCES app_user(id),
    token_hash  varchar(64) NOT NULL UNIQUE,
    family_id   uuid NOT NULL,
    issued_at   timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    revoked_at  timestamptz,
    ip          varchar(64),
    user_agent  text,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE INDEX refresh_token_family_idx ON refresh_token (tenant_id, family_id);

SELECT enable_tenant_rls('refresh_token');
-- No DELETE: tokens are retired via used_at / revoked_at, never removed, so
-- reuse detection can still recognise a replayed token after rotation.
GRANT SELECT, INSERT, UPDATE ON refresh_token TO onboarding_app;
```

- [ ] **Step 4: Implement `RefreshTokenService`**

```java
package co.ara.onboarding.auth;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class RefreshTokenService {

    public record RotationResult(AppUser user, String newRawToken) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final AppUserRepository users;
    private final AuditRecorder audit;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository, AppUserRepository users,
                               AuditRecorder audit,
                               @Value("${app.refresh-token.ttl}") Duration ttl) {
        this.repository = repository;
        this.users = users;
        this.audit = audit;
        this.ttl = ttl;
    }

    @Transactional
    public String issue(AppUser user, String ip, String userAgent) {
        return issueInFamily(user, UUID.randomUUID(), ip, userAgent);
    }

    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken stored = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new RefreshTokenReuseException("Unknown refresh token"));

        if (stored.getRevokedAt() != null || stored.getUsedAt() != null) {
            // Replay of a retired token: assume theft and kill the family.
            revokeFamily(stored.getFamilyId());
            audit.record(AuditActions.REFRESH_REUSE_DETECTED, "app_user", stored.getUserId(),
                    "Refresh token reuse detected; session family revoked",
                    Map.of("familyId", stored.getFamilyId().toString()));
            throw new RefreshTokenReuseException("Refresh token reuse detected");
        }

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            revokeFamily(stored.getFamilyId());
            throw new RefreshTokenReuseException("Refresh token expired");
        }

        stored.setUsedAt(Instant.now());
        repository.save(stored);

        AppUser user = users.findById(stored.getUserId())
                .orElseThrow(() -> new RefreshTokenReuseException("Unknown user"));
        String next = issueInFamily(user, stored.getFamilyId(), stored.getIp(), stored.getUserAgent());
        return new RotationResult(user, next);
    }

    @Transactional
    public void revokeFamily(UUID familyId) {
        repository.findByFamilyId(familyId).forEach(t -> {
            if (t.getRevokedAt() == null) {
                t.setRevokedAt(Instant.now());
                repository.save(t);
            }
        });
    }

    private String issueInFamily(AppUser user, UUID familyId, String ip, String userAgent) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken token = new RefreshToken();
        token.setId(UUID.randomUUID());
        token.setTenantId(user.getTenantId());
        token.setUserId(user.getId());
        token.setTokenHash(hash(raw));
        token.setFamilyId(familyId);
        token.setIssuedAt(Instant.now());
        token.setExpiresAt(Instant.now().plus(ttl));
        token.setIp(ip);
        token.setUserAgent(userAgent);
        repository.save(token);

        return raw;   // returned once; only the hash is persisted
    }

    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`RefreshTokenReuseException extends RuntimeException`; map it to 401 in `ApiExceptionHandler`.

- [ ] **Step 5: Add refresh and logout endpoints**

Add to `AuthController`. On login, also issue a refresh cookie:

```java
    // Injected on the controller; the same value the refresh token is issued with.
    private final Duration refreshTtl;

    public AuthController(/* existing dependencies */,
                          RefreshTokenService refreshTokens,
                          @Value("${app.refresh-token.ttl}") Duration refreshTtl) {
        // ...
        this.refreshTokens = refreshTokens;
        this.refreshTtl = refreshTtl;
    }

    private ResponseCookie refreshCookie(String tenantSlug, String rawToken, Duration maxAge) {
        return ResponseCookie.from("refresh_token", rawToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/t/" + tenantSlug + "/auth")
                .maxAge(maxAge)
                .build();
    }

    @PostMapping("/refresh")
    @Transactional
    public ResponseEntity<LoginResponse> refresh(
            @PathVariable String tenantSlug,
            @CookieValue(name = "refresh_token", required = false) String rawToken) {

        if (rawToken == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var result = refreshTokens.rotate(rawToken);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(tenantSlug, result.newRawToken(), refreshTtl).toString())
                .body(new LoginResponse(tokens.issueAccessToken(result.user()),
                        tokens.ttlSeconds(),
                        Map.of("id", result.user().getId(),
                               "fullName", result.user().getFullName(),
                               "userType", result.user().getUserType().name())));
    }

    @PostMapping("/logout")
    @Transactional
    public ResponseEntity<Void> logout(
            @PathVariable String tenantSlug,
            @CookieValue(name = "refresh_token", required = false) String rawToken) {

        if (rawToken != null) refreshTokens.revokeByRawToken(rawToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(tenantSlug, "", Duration.ZERO).toString())
                .build();
    }
```

Add `revokeByRawToken(String)` to `RefreshTokenService`, which looks the token up by hash and revokes its family. Logout revoking the whole family is intentional — logging out should end the session everywhere it was rotated.

- [ ] **Step 6: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.auth.RefreshTokenTest"`
Expected: PASS — all four tests.

- [ ] **Step 7: Commit**

```bash
git add backend/
git commit -m "feat: add refresh token rotation with reuse detection"
```

---

### Task 17: Login throttling and lockout

Progressive delay and lockout counted in PostgreSQL, since Redis is out of scope.

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__login_attempt.sql`
- Create: `backend/src/main/java/co/ara/onboarding/auth/LoginAttempt.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/LoginAttemptRepository.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/LoginThrottleService.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/TooManyAttemptsException.java`
- Modify: `backend/src/main/java/co/ara/onboarding/auth/AuthController.java`
- Modify: `backend/src/main/java/co/ara/onboarding/platform/ApiExceptionHandler.java`
- Test: `backend/src/test/java/co/ara/onboarding/auth/LoginThrottleTest.java`

**Interfaces:**
- Consumes: `AuditRecorder`.
- Produces:
  - `LoginThrottleService.checkAllowed(UUID tenantId, String email)` — throws `TooManyAttemptsException` (→ 429) when locked out.
  - `LoginThrottleService.recordFailure(UUID tenantId, String email)`, `.recordSuccess(UUID tenantId, String email)`.
  - Policy: lockout after **5** consecutive failures within **15 minutes**, for **15 minutes**. A success clears the counter.

- [ ] **Step 1: Write the failing test**

```java
package co.ara.onboarding.auth;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottleTest extends PostgresTestBase {

    @Autowired LoginThrottleService throttle;
    @Autowired TenantFixture fixture;

    @Test
    void locksOutAfterFiveConsecutiveFailures() {
        UUID tenant = fixture.createTenant("throttle-co");
        fixture.runAs(tenant, () -> {
            for (int i = 0; i < 5; i++) throttle.recordFailure(tenant, "target@example.com");
            assertThatThrownBy(() -> throttle.checkAllowed(tenant, "target@example.com"))
                    .isInstanceOf(TooManyAttemptsException.class);
        });
    }

    @Test
    void successClearsTheCounter() {
        UUID tenant = fixture.createTenant("throttle-clear");
        fixture.runAs(tenant, () -> {
            for (int i = 0; i < 4; i++) throttle.recordFailure(tenant, "clear@example.com");
            throttle.recordSuccess(tenant, "clear@example.com");
            for (int i = 0; i < 4; i++) throttle.recordFailure(tenant, "clear@example.com");
            assertThatCode(() -> throttle.checkAllowed(tenant, "clear@example.com"))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void lockoutIsScopedPerTenantAndEmail() {
        UUID tenantA = fixture.createTenant("throttle-a");
        UUID tenantB = fixture.createTenant("throttle-b");
        fixture.runAs(tenantA, () -> {
            for (int i = 0; i < 5; i++) throttle.recordFailure(tenantA, "same@example.com");
        });
        fixture.runAs(tenantB, () ->
            assertThatCode(() -> throttle.checkAllowed(tenantB, "same@example.com"))
                    .doesNotThrowAnyException());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.auth.LoginThrottleTest"`
Expected: FAIL — throttle classes do not exist.

- [ ] **Step 3: Write the migration**

```sql
CREATE TABLE login_attempt (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    email          varchar(320) NOT NULL,
    failure_count  int NOT NULL DEFAULT 0,
    first_failure  timestamptz,
    locked_until   timestamptz,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, email)
);

SELECT enable_tenant_rls('login_attempt');
GRANT SELECT, INSERT, UPDATE ON login_attempt TO onboarding_app;
-- Explicit DELETE (deny-by-default since V2_1): a successful login clears the
-- counter by removing the row. Throttling state, not a business record.
GRANT DELETE ON login_attempt TO onboarding_app;
```

- [ ] **Step 4: Implement `LoginThrottleService`**

Policy constants: `MAX_FAILURES = 5`, `WINDOW = Duration.ofMinutes(15)`, `LOCKOUT = Duration.ofMinutes(15)`.

`recordFailure` increments the counter, sets `firstFailure` when starting a new window, resets the window when the previous one has elapsed, and sets `lockedUntil` when the counter reaches `MAX_FAILURES`. `recordSuccess` deletes the row. `checkAllowed` throws when `lockedUntil` is in the future.

Counting per `(tenant, email)` rather than per IP is deliberate: it protects accounts against distributed guessing, which IP-based counting does not.

- [ ] **Step 5: Wire it into `AuthController.login`**

Call `throttle.checkAllowed(...)` before verifying the password, `throttle.recordFailure(...)` on the failure path, and `throttle.recordSuccess(...)` after a successful login. Map `TooManyAttemptsException` to 429 in `ApiExceptionHandler`.

- [ ] **Step 6: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.auth.LoginThrottleTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/
git commit -m "feat: add per-account login throttling and lockout"
```

---

### Task 18: Email, invitations, activation, and password reset

Invitation-based provisioning per QA Q12. The narrow `EmailSender` interface exists so the full notification system in sub-project 6 does not leak backwards into this one.

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__invitation.sql`
- Create: `backend/src/main/java/co/ara/onboarding/auth/EmailSender.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/EmailMessage.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/SmtpEmailSender.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/LoggingEmailSender.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/Invitation.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/InvitationRepository.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/InvitationService.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/PasswordResetService.java`
- Create: `backend/src/main/java/co/ara/onboarding/auth/InvalidTokenException.java`
- Modify: `backend/src/main/java/co/ara/onboarding/auth/AuthController.java`
- Modify: `backend/src/main/java/co/ara/onboarding/platform/ApiExceptionHandler.java`
- Test: `backend/src/test/java/co/ara/onboarding/auth/InvitationFlowTest.java`

**Interfaces:**
- Consumes: `CustomerContactRepository`, `AppUserRepository`, `PasswordEncoder`, `AuditRecorder`.
- Produces:
  - `EmailMessage` — `record EmailMessage(String to, String subject, String body)`.
  - `EmailSender.send(EmailMessage)`. `LoggingEmailSender` is active under profile `dev` and in tests; `SmtpEmailSender` otherwise.
  - `InvitationService.issue(UUID contactId)` → raw token; `.accept(String rawToken, String password)` → `AppUser`.
  - `PasswordResetService.request(String email)`, `.reset(String rawToken, String newPassword)` — same token table, `purpose` column distinguishes them.
  - `POST /api/t/{slug}/auth/activate`, `POST /api/t/{slug}/auth/password-reset/request`, `POST /api/t/{slug}/auth/password-reset/confirm`.

- [ ] **Step 1: Write the failing test**

```java
package co.ara.onboarding.auth;

import co.ara.onboarding.customer.*;
import co.ara.onboarding.identity.*;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationFlowTest extends PostgresTestBase {

    @Autowired InvitationService invitations;
    @Autowired CustomerContactRepository contacts;
    @Autowired AppUserRepository users;
    @Autowired TenantFixture fixture;

    @Test
    void acceptingAnInvitationCreatesAPortalUserAndLinksTheContact() {
        UUID tenant = fixture.createTenant("invite-co");
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Invitee Ltd", null, null, null);
            UUID contactId = fixture.createContact(tenant, customerId, "guest@invitee.example");

            String token = invitations.issue(contactId);
            AppUser created = invitations.accept(token, "a-sufficiently-long-password");

            assertThat(created.getUserType()).isEqualTo(UserType.PORTAL);
            assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(contacts.findById(contactId).orElseThrow().getUserId())
                    .isEqualTo(created.getId());
        });
    }

    @Test
    void anInvitationTokenCannotBeUsedTwice() {
        UUID tenant = fixture.createTenant("single-use");
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Once Ltd", null, null, null);
            UUID contactId = fixture.createContact(tenant, customerId, "once@example.com");

            String token = invitations.issue(contactId);
            invitations.accept(token, "a-sufficiently-long-password");

            assertThatThrownBy(() -> invitations.accept(token, "another-password"))
                    .isInstanceOf(InvalidTokenException.class);
        });
    }

    @Test
    void expiredInvitationIsRejected() {
        UUID tenant = fixture.createTenant("expired-invite");
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Late Ltd", null, null, null);
            UUID contactId = fixture.createContact(tenant, customerId, "late@example.com");

            String token = invitations.issue(contactId);
            fixture.expireInvitations();

            assertThatThrownBy(() -> invitations.accept(token, "a-sufficiently-long-password"))
                    .isInstanceOf(InvalidTokenException.class);
        });
    }

    @Test
    void createdPortalUserCannotHoldInternalRoles() {
        UUID tenant = fixture.createTenant("portal-type");
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Portal Ltd", null, null, null);
            UUID contactId = fixture.createContact(tenant, customerId, "portal@example.com");
            AppUser created = invitations.accept(invitations.issue(contactId), "long-enough-password");
            assertThat(created.getUserType()).isEqualTo(UserType.PORTAL);
        });
    }
}
```

Add `createContact(UUID tenantId, UUID customerId, String email)` and `expireInvitations()` to `TenantFixture`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.auth.InvitationFlowTest"`
Expected: FAIL — invitation classes do not exist.

- [ ] **Step 3: Write the migration**

```sql
CREATE TABLE invitation (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    purpose              varchar(24) NOT NULL,   -- ACTIVATION | PASSWORD_RESET
    customer_contact_id  uuid REFERENCES customer_contact(id),
    user_id              uuid REFERENCES app_user(id),
    token_hash           varchar(64) NOT NULL UNIQUE,
    expires_at           timestamptz NOT NULL,
    accepted_at          timestamptz,
    revoked_at           timestamptz,
    created_by           uuid REFERENCES app_user(id),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL
);

SELECT enable_tenant_rls('invitation');
GRANT SELECT, INSERT, UPDATE ON invitation TO onboarding_app;
```

Activation invitations carry `customer_contact_id`; password resets carry `user_id`. Both are nullable because exactly one applies.

- [ ] **Step 4: Implement `EmailSender` and its two implementations**

```java
package co.ara.onboarding.auth;

public record EmailMessage(String to, String subject, String body) {}
```

```java
package co.ara.onboarding.auth;

public interface EmailSender {
    void send(EmailMessage message);
}
```

`LoggingEmailSender` is `@Component` `@Profile({"dev","test"})` and logs subject, recipient, and body at INFO. `SmtpEmailSender` is `@Component` `@Profile("!dev & !test")` and uses `JavaMailSender`.

Token lifetimes: activation invitations expire after **7 days**, password resets after **1 hour**.

- [ ] **Step 5: Implement `InvitationService`**

Reuse the SHA-256 hashing approach from `RefreshTokenService` — the raw token is returned once and emailed; only the hash is stored. `accept` validates that `acceptedAt` and `revokedAt` are null and `expiresAt` is in the future, creates the `PORTAL` `AppUser` with `UserStatus.ACTIVE`, links `customer_contact.user_id`, stamps `acceptedAt`, and records `AuditActions.INVITATION_ACCEPTED`.

`InvalidTokenException extends RuntimeException`, mapped to 400.

- [ ] **Step 6: Add the endpoints**

`POST /auth/activate` with body `{token, password}`; `POST /auth/password-reset/request` with `{email}` — always returns 204 regardless of whether the address exists, so the endpoint cannot be used to enumerate accounts; `POST /auth/password-reset/confirm` with `{token, password}`.

Password policy: minimum 12 characters, validated with `@Size(min = 12)`.

- [ ] **Step 7: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.auth.InvitationFlowTest"`
Expected: PASS — all four tests.

- [ ] **Step 8: Commit**

```bash
git add backend/
git commit -m "feat: add invitation, activation and password reset flows"
```

---

### Task 19: The `/me` endpoint and OpenAPI generation

Supplies the frontend's permission-aware UI and produces the contract the frontend types are generated from.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/auth/MeController.java`
- Modify: `backend/build.gradle.kts`
- Test: `backend/src/test/java/co/ara/onboarding/auth/MeControllerTest.java`

**Interfaces:**
- Consumes: `AuthorizationService`, `AuthContextProvider`.
- Produces:
  - `GET /api/t/{slug}/me` → `{id, fullName, email, userType, departmentId, teamIds, permissions: {"customer.view": ["TEAM","ASSIGNED"], ...}}`.
  - A Gradle task `openApiSpec` writing `build/openapi.json`, consumed by the frontend in Task 23.

- [ ] **Step 1: Write the failing test**

```java
package co.ara.onboarding.auth;

import co.ara.onboarding.authz.*;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class MeControllerTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired RoleService roles;
    @Autowired TokenService tokens;
    @Autowired TenantFixture fixture;

    @Test
    void meReturnsEffectivePermissionsWithScopes() throws Exception {
        UUID tenant = fixture.createTenant("me-co");
        var user = fixture.createUserWithPassword(tenant, "me@example.com", "long-enough-password");

        fixture.runAs(tenant, () -> {
            UUID role = roles.createRole("Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            roles.assignRole(user.getId(), role);
        });

        mvc.perform(get("/api/t/me-co/me")
                    .header("Authorization", "Bearer " + tokens.issueAccessToken(user)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.permissions['customer.view']").value("TEAM"))
           .andExpect(jsonPath("$.userType").value("INTERNAL"));
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        fixture.createTenant("me-anon");
        mvc.perform(get("/api/t/me-anon/me")).andExpect(status().isUnauthorized());
    }
}
```

Note the JSON path expects a single value because `jsonPath` on a one-element array unwraps it; if the assertion proves awkward, assert on `$.permissions['customer.view'][0]` instead.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.auth.MeControllerTest"`
Expected: FAIL — `MeController` does not exist.

- [ ] **Step 3: Implement `MeController`**

```java
package co.ara.onboarding.auth;

import co.ara.onboarding.authz.*;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/t/{tenantSlug}/me")
public class MeController {

    private final AuthContextProvider contextProvider;
    private final AuthorizationService authorization;
    private final AppUserRepository users;

    public MeController(AuthContextProvider contextProvider,
                        AuthorizationService authorization,
                        AppUserRepository users) {
        this.contextProvider = contextProvider;
        this.authorization = authorization;
        this.users = users;
    }

    @GetMapping
    public Map<String, Object> me() {
        AppUser user = users.findById(contextProvider.principal().userId()).orElseThrow();

        Map<String, List<String>> permissions = authorization.effectivePermissions()
                .byPermission().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().stream().map(Enum::name).sorted().toList()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("fullName", user.getFullName());
        body.put("email", user.getEmail());
        body.put("userType", user.getUserType().name());
        body.put("departmentId", user.getDepartmentId());
        body.put("teamIds", user.getTeamIds());
        body.put("permissions", permissions);
        return body;
    }
}
```

This response drives UI affordances only. It is convenience, never security — every endpoint enforces independently (spec §10.3).

- [ ] **Step 4: Add the OpenAPI export task**

Add to `build.gradle.kts`:

```kotlin
tasks.register("openApiSpec") {
    description = "Writes build/openapi.json for frontend type generation"
    dependsOn("bootJar")
    doLast {
        // springdoc serves the document at /v3/api-docs when the app runs.
        // Fetch it with the app started, or run:
        //   curl -s http://localhost:8080/v3/api-docs > build/openapi.json
        println("Start the app and run: curl -s http://localhost:8080/v3/api-docs > build/openapi.json")
    }
}
```

Generating the document requires a running application. Task 23 documents the exact frontend command that consumes it.

- [ ] **Step 5: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.auth.MeControllerTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/
git commit -m "feat: add /me endpoint exposing effective permissions"
```

---

### Task 20: Customer and contact operations

The first real business surface, and the first place every mechanism built so far runs together.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerService.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerContactService.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerController.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerContactController.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/dto/` (request/response records)
- Test: `backend/src/test/java/co/ara/onboarding/customer/CustomerServiceTest.java`

**Interfaces:**
- Consumes: `AuthorizedQuery`, `AuthContextProvider`, `AuditRecorder`, `InvitationService`, `EmailSender`.
- Produces:
  - `CustomerService.create(CreateCustomerRequest)`, `.list(String search, CustomerStatus status, Pageable)`, `.get(UUID)`, `.update(UUID, UpdateCustomerRequest)`, `.deactivate(UUID, String reason)`.
  - `CustomerContactService.list(UUID customerId)`, `.create(UUID customerId, CreateContactRequest)`, `.update(UUID contactId, UpdateContactRequest)`, `.sendInvitation(UUID contactId)`.
  - Endpoints under `/api/t/{slug}/customers` and `/api/t/{slug}/customers/{customerId}/contacts`.
  - `DELETE` is **not** exposed. Deactivation is `POST /customers/{id}/deactivate` (spec §9.4).

- [ ] **Step 1: Write the failing test**

```java
package co.ara.onboarding.customer;

import co.ara.onboarding.authz.*;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerServiceTest extends PostgresTestBase {

    @Autowired CustomerService customers;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;

    @Test
    void createRequiresTheCreatePermission() {
        UUID tenant = fixture.createTenant("cust-create-denied");
        UUID user = fixture.createUser(tenant, "nocreate@example.com");

        fixture.runAsUser(tenant, user, () ->
            assertThatThrownBy(() -> customers.create(
                    new CustomerService.CreateCustomerRequest(
                            "Denied Ltd", "Denied", null, null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class));
    }

    @Test
    void listReturnsOnlyRecordsInScope() {
        UUID tenant = fixture.createTenant("cust-list-scope");
        UUID user = fixture.createUser(tenant, "scoped@example.com");

        fixture.runAs(tenant, () -> {
            fixture.createCustomer(tenant, "Mine", user, null, null);
            fixture.createCustomer(tenant, "Theirs", null, null, null);
            UUID role = roles.createRole("Assigned Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user, role);
        });

        fixture.runAsUser(tenant, user, () ->
            assertThat(customers.list(null, null, Pageable.unpaged()))
                    .extracting(c -> c.displayName()).containsExactly("Mine"));
    }

    @Test
    void deactivationSetsStatusAndWritesAudit() {
        UUID tenant = fixture.createTenant("cust-deactivate");
        UUID user = fixture.createUser(tenant, "deact@example.com");

        UUID[] customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Closing Ltd", user, null, null);
            UUID role = roles.createRole("Deactivator", "", Map.of(
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL,
                    PermissionKeys.CUSTOMER_DEACTIVATE, Scope.ALL));
            roles.assignRole(user, role);
        });

        fixture.runAsUser(tenant, user, () -> {
            customers.deactivate(customerId[0], "Contract ended");
            assertThat(customers.get(customerId[0]).status()).isEqualTo(CustomerStatus.INACTIVE);
        });
    }

    @Test
    void getOutOfScopeCustomerIsNotFound() {
        UUID tenant = fixture.createTenant("cust-get-404");
        UUID user = fixture.createUser(tenant, "limited@example.com");
        UUID other = fixture.createUser(tenant, "otherowner@example.com");

        UUID[] hidden = new UUID[1];
        fixture.runAs(tenant, () -> {
            hidden[0] = fixture.createCustomer(tenant, "Hidden", other, null, null);
            UUID role = roles.createRole("Assigned Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user, role);
        });

        fixture.runAsUser(tenant, user, () ->
            assertThatThrownBy(() -> customers.get(hidden[0]))
                    .isInstanceOf(NoSuchElementException.class));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.customer.CustomerServiceTest"`
Expected: FAIL — `CustomerService` does not exist.

- [ ] **Step 3: Implement `CustomerService`**

```java
package co.ara.onboarding.customer;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.*;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerService {

    public record CreateCustomerRequest(String legalName, String displayName, String industry,
                                        String country, String externalRef,
                                        UUID owningDepartmentId, UUID owningTeamId) {}

    public record UpdateCustomerRequest(String legalName, String displayName, String industry,
                                        String country, String externalRef, UUID ownerUserId,
                                        UUID owningDepartmentId, UUID owningTeamId) {}

    public record CustomerView(UUID id, String legalName, String displayName,
                               CustomerStatus status, String industry, String country,
                               UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId) {}

    private final CustomerRepository repository;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;

    public CustomerService(CustomerRepository repository, AuthorizedQuery authorizedQuery,
                           AuthContextProvider contextProvider, AuditRecorder audit) {
        this.repository = repository;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
    }

    @RequirePermission(PermissionKeys.CUSTOMER_CREATE)
    @Transactional
    public CustomerView create(CreateCustomerRequest request) {
        UUID actor = contextProvider.principal().userId();
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setTenantId(TenantContext.getRequired());
        c.setLegalName(request.legalName());
        c.setDisplayName(request.displayName());
        c.setStatus(CustomerStatus.PROSPECT);
        c.setIndustry(request.industry());
        c.setCountry(request.country());
        c.setExternalRef(request.externalRef());
        // The creator becomes the owner by default, which is what makes
        // ASSIGNED scope useful immediately after creation.
        c.setOwnerUserId(actor);
        c.setCreatedBy(actor);
        c.setOwningDepartmentId(request.owningDepartmentId());
        c.setOwningTeamId(request.owningTeamId());
        repository.save(c);

        audit.record(AuditActions.CUSTOMER_CREATED, "customer", c.getId(),
                "Created customer " + c.getDisplayName(),
                Map.of("legalName", c.getLegalName()));
        return toView(c);
    }

    @RequirePermission(PermissionKeys.CUSTOMER_VIEW)
    @Transactional(readOnly = true)
    public Page<CustomerView> list(String search, CustomerStatus status, Pageable pageable) {
        Specification<Customer> filters = (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicate = cb.and(predicate, cb.or(
                        cb.like(cb.lower(root.get("displayName")), pattern),
                        cb.like(cb.lower(root.get("legalName")), pattern)));
            }
            if (status != null) predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            return predicate;
        };
        return authorizedQuery.findAll(repository, Customer.class,
                PermissionKeys.CUSTOMER_VIEW, filters, pageable).map(this::toView);
    }

    @RequirePermission(PermissionKeys.CUSTOMER_VIEW)
    @Transactional(readOnly = true)
    public CustomerView get(UUID id) {
        return toView(authorizedQuery.getById(repository, Customer.class,
                PermissionKeys.CUSTOMER_VIEW, id));
    }

    @RequirePermission(PermissionKeys.CUSTOMER_EDIT)
    @Transactional
    public CustomerView update(UUID id, UpdateCustomerRequest request) {
        Customer c = authorizedQuery.getById(repository, Customer.class,
                PermissionKeys.CUSTOMER_EDIT, id);
        c.setLegalName(request.legalName());
        c.setDisplayName(request.displayName());
        c.setIndustry(request.industry());
        c.setCountry(request.country());
        c.setExternalRef(request.externalRef());
        c.setOwnerUserId(request.ownerUserId());
        c.setOwningDepartmentId(request.owningDepartmentId());
        c.setOwningTeamId(request.owningTeamId());
        repository.save(c);

        audit.record(AuditActions.CUSTOMER_UPDATED, "customer", c.getId(),
                "Updated customer " + c.getDisplayName(), Map.of());
        return toView(c);
    }

    @RequirePermission(PermissionKeys.CUSTOMER_DEACTIVATE)
    @Transactional
    public void deactivate(UUID id, String reason) {
        Customer c = authorizedQuery.getById(repository, Customer.class,
                PermissionKeys.CUSTOMER_DEACTIVATE, id);
        c.setStatus(CustomerStatus.INACTIVE);
        repository.save(c);
        // Never delete: erasure is satisfied by pseudonymization (spec 9.4, QA Q11).
        audit.record(AuditActions.CUSTOMER_DEACTIVATED, "customer", c.getId(),
                "Deactivated customer " + c.getDisplayName(),
                Map.of("reason", reason == null ? "" : reason));
    }

    private CustomerView toView(Customer c) {
        return new CustomerView(c.getId(), c.getLegalName(), c.getDisplayName(), c.getStatus(),
                c.getIndustry(), c.getCountry(), c.getOwnerUserId(),
                c.getOwningDepartmentId(), c.getOwningTeamId());
    }
}
```

Note `update` and `deactivate` fetch through `authorizedQuery.getById` with the *write* permission, not `CUSTOMER_VIEW`. Fetching with a read permission and then writing is a privilege-escalation bug; a user who can see a record is not necessarily allowed to change it.

- [ ] **Step 4: Implement `CustomerContactService`**

Same shape. `list` and `create` gate on `CONTACT_VIEW` / `CONTACT_MANAGE`. `sendInvitation` gates on `INVITATION_SEND`, calls `InvitationService.issue(contactId)`, sends the email through `EmailSender`, and records `AuditActions.INVITATION_SENT`.

- [ ] **Step 5: Implement the controllers**

Thin controllers that bind path variables and delegate. No authorization logic in controllers — that is the gate's job, and the ArchUnit rule from Task 7 enforces it.

```java
package co.ara.onboarding.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/t/{tenantSlug}/customers")
public class CustomerController {

    private final CustomerService customers;

    public CustomerController(CustomerService customers) { this.customers = customers; }

    @GetMapping
    public Page<CustomerService.CustomerView> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) CustomerStatus status,
            Pageable pageable) {
        return customers.list(search, status, pageable);
    }

    @GetMapping("/{id}")
    public CustomerService.CustomerView get(@PathVariable UUID id) { return customers.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerService.CustomerView create(
            @RequestBody CustomerService.CreateCustomerRequest request) {
        return customers.create(request);
    }

    @PutMapping("/{id}")
    public CustomerService.CustomerView update(
            @PathVariable UUID id,
            @RequestBody CustomerService.UpdateCustomerRequest request) {
        return customers.update(id, request);
    }

    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        customers.deactivate(id, body == null ? null : body.get("reason"));
    }
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.customer.*"`
Expected: PASS

- [ ] **Step 7: Remove the debug endpoint from Task 4**

Delete `TenantDebugController` and the `_debug` assertions in `TenantResolutionTest`, replacing them with assertions against `/api/t/{slug}/customers` returning 401 when unauthenticated. The tenant pipeline is now covered by real endpoints.

- [ ] **Step 8: Commit**

```bash
git add backend/
git commit -m "feat: add customer and contact operations with scoped authorization"
```

---

### Task 21: Tenant administration endpoints

The screens in Task 28 need these: users, roles and grants, departments, teams.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/identity/UserAdminService.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/UserAdminController.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/OrgStructureService.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/OrgStructureController.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/RoleController.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/PermissionCatalogController.java`
- Test: `backend/src/test/java/co/ara/onboarding/identity/UserAdminTest.java`

**Interfaces:**
- Consumes: `AuthorizedQuery`, `RoleService`, `PermissionCatalog`, `InvitationService`.
- Produces:
  - `GET/POST/PUT /api/t/{slug}/admin/users`, `POST /admin/users/{id}/roles`, `POST /admin/users/{id}/deactivate`.
  - `GET/POST/PUT /api/t/{slug}/admin/roles`, `POST /admin/roles/{id}/disable`, `DELETE /admin/roles/{id}`.
  - `GET/POST /api/t/{slug}/admin/departments`, `GET/POST /api/t/{slug}/admin/teams`.
  - `GET /api/t/{slug}/admin/permissions` — the catalog with each permission's `allowedScopes`, so the role editor can only offer valid combinations.

- [ ] **Step 1: Write the failing test**

```java
package co.ara.onboarding.identity;

import co.ara.onboarding.authz.*;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAdminTest extends PostgresTestBase {

    @Autowired UserAdminService userAdmin;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;

    @Test
    void listingUsersRequiresUserViewPermission() {
        UUID tenant = fixture.createTenant("admin-denied");
        UUID user = fixture.createUser(tenant, "plain@example.com");

        fixture.runAsUser(tenant, user, () ->
            assertThatThrownBy(() -> userAdmin.list(null, org.springframework.data.domain.Pageable.unpaged()))
                .isInstanceOf(AccessDeniedException.class));
    }

    @Test
    void departmentScopedAdminSeesOnlyOwnDepartmentUsers() {
        UUID tenant = fixture.createTenant("admin-dept-scope");
        UUID deptA = fixture.createDepartment(tenant, "Dept A");
        UUID deptB = fixture.createDepartment(tenant, "Dept B");
        UUID admin = fixture.createUserInDepartment(tenant, "admin@example.com", deptA);
        fixture.createUserInDepartment(tenant, "peer@example.com", deptA);
        fixture.createUserInDepartment(tenant, "outsider@example.com", deptB);

        fixture.runAs(tenant, () -> {
            UUID role = roles.createRole("Dept Admin", "",
                    Map.of(PermissionKeys.USER_VIEW, Scope.DEPARTMENT));
            roles.assignRole(admin, role);
        });

        fixture.runAsUser(tenant, admin, () ->
            assertThat(userAdmin.list(null, org.springframework.data.domain.Pageable.unpaged()))
                .extracting(u -> u.email())
                .containsExactlyInAnyOrder("admin@example.com", "peer@example.com"));
    }
}
```

Add `createDepartment(UUID, String)` and `createUserInDepartment(UUID, String, UUID)` to `TenantFixture`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.identity.UserAdminTest"`
Expected: FAIL — `UserAdminService` does not exist.

- [ ] **Step 3: Implement the services and controllers**

Follow the exact pattern established in Task 20: `@RequirePermission` on every public service method, all reads through `AuthorizedQuery` with the matching permission key, writes fetched with the write permission, and an audit event per mutation.

`UserAdminService.create` creates an `INTERNAL` user in `INVITED` status and issues an activation invitation through `InvitationService`, reusing the same token machinery as customer contacts rather than adding a parallel flow.

`PermissionCatalogController` is read-only and returns `PermissionCatalog.all()` with `allowedScopes` — the role editor uses it to offer only valid scope options per permission, so invalid combinations are rejected in the UI as well as at the API.

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.identity.UserAdminTest"`
Expected: PASS

- [ ] **Step 5: Run the whole backend suite**

Run: `cd backend && ./gradlew test`
Expected: PASS, including all architecture tests.

- [ ] **Step 6: Commit**

```bash
git add backend/
git commit -m "feat: add tenant administration endpoints for users, roles and org structure"
```

---

### Task 22: The eight negative security tests

The completion gate for this sub-project (spec §11.3, §12). If these are not green, sub-project 1 is not done regardless of how much UI works.

**Files:**
- Create: `backend/src/test/java/co/ara/onboarding/security/CrossTenantAccessTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/security/InsufficientPermissionTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/security/InsufficientScopeTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/security/MultipleRolesTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/security/ConflictingGrantsTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/security/RoleLifecycleTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/security/ChangedPermissionsTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/security/DirectApiAccessTest.java`
- Modify: `backend/src/main/java/co/ara/onboarding/platform/SecurityConfig.java`

**Interfaces:**
- Consumes: everything.
- Produces: no production interfaces. This task hardens `/api/platform/**` behind platform-admin authentication as its only production change.

Every test in this task goes through **MockMvc against real HTTP endpoints**, not through services directly. Testing at the service layer would miss exactly the bypasses these tests exist to catch.

- [ ] **Step 1: Test 1 — cross-tenant access**

```java
package co.ara.onboarding.security;

import co.ara.onboarding.auth.TokenService;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CrossTenantAccessTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Autowired TenantFixture fixture;

    @Test
    void tokenFromTenantACannotBeUsedAgainstTenantB() throws Exception {
        UUID tenantA = fixture.createTenant("sec-tenant-a");
        UUID tenantB = fixture.createTenant("sec-tenant-b");
        var userA = fixture.createAdminUser(tenantA, "a@example.com");

        mvc.perform(get("/api/t/sec-tenant-b/customers")
                    .header("Authorization", "Bearer " + tokens.issueAccessToken(userA)))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void customerOfAnotherTenantIsNotReachableByDirectId() throws Exception {
        UUID tenantA = fixture.createTenant("sec-owner");
        UUID tenantB = fixture.createTenant("sec-intruder");
        var adminA = fixture.createAdminUser(tenantA, "owner@example.com");
        var adminB = fixture.createAdminUser(tenantB, "intruder@example.com");

        UUID[] customerId = new UUID[1];
        fixture.runAs(tenantA, () ->
            customerId[0] = fixture.createCustomer(tenantA, "Tenant A Customer", null, null, null));

        mvc.perform(get("/api/t/sec-intruder/customers/" + customerId[0])
                    .header("Authorization", "Bearer " + tokens.issueAccessToken(adminB)))
           .andExpect(status().isNotFound());
    }

    @Test
    void listingNeverLeaksAnotherTenantsRows() throws Exception {
        UUID tenantA = fixture.createTenant("sec-list-a");
        UUID tenantB = fixture.createTenant("sec-list-b");
        var adminB = fixture.createAdminUser(tenantB, "listb@example.com");

        fixture.runAs(tenantA, () -> fixture.createCustomer(tenantA, "Secret A", null, null, null));

        mvc.perform(get("/api/t/sec-list-b/customers")
                    .header("Authorization", "Bearer " + tokens.issueAccessToken(adminB)))
           .andExpect(status().isOk())
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                   .jsonPath("$.content").isEmpty());
    }
}
```

Add `createAdminUser(UUID tenantId, String email)` to `TenantFixture`, returning an `ACTIVE` `INTERNAL` user holding the seeded Administrator role.

- [ ] **Step 2: Test 2 — insufficient permission**

`InsufficientPermissionTest`: an authenticated user with no roles receives **403** from `POST /customers`, `GET /admin/users`, and `POST /admin/roles`. Assert the response body contains no permission name — the message must not teach a caller which permission would unlock the endpoint.

- [ ] **Step 3: Test 3 — correct permission, insufficient scope**

`InsufficientScopeTest`: a user holding `customer.view` at `TEAM` requests a customer owned by a different team and receives **404, not 403**. Assert the status is exactly 404, and assert that the same user receives 200 for a customer owned by their own team — otherwise the test would pass against a system that simply denies everything.

- [ ] **Step 4: Test 4 — multiple roles**

`MultipleRolesTest`: a user holds one role granting `customer.view` at `TEAM` and another at `ASSIGNED`. Assert the list contains a record reachable through *only* the team grant, a record reachable through *only* the personal-owner grant, and excludes a record reachable through neither. This is the test that proves scopes union rather than nest.

- [ ] **Step 5: Test 5 — conflicting role grants**

`ConflictingGrantsTest`: the same permission granted at `ALL` in one role and `ASSIGNED` in another resolves to `ALL`, deterministically, regardless of role creation order. Run the assertion twice with the roles assigned in opposite order to prove there is no precedence dependency.

- [ ] **Step 6: Test 6 — disabled and deleted roles**

`RoleLifecycleTest`:
- Disabling a role removes its authority on the very next request.
- `DELETE /admin/roles/{id}` returns **409** while users are assigned.
- After unassigning every user, deletion succeeds.
- Re-enabling a disabled role restores authority.

- [ ] **Step 7: Test 7 — changed role permissions**

`ChangedPermissionsTest`: with a session already established, revoke a grant through `PUT /admin/roles/{id}`, then reuse the *same unexpired access token*. The request must be refused. This is the test that proves permissions are not embedded in the token and not cached across requests.

- [ ] **Step 8: Test 8 — direct API access bypassing the UI**

`DirectApiAccessTest`:
- A `PORTAL` user with a valid token receives **403** from `GET /admin/users`, `POST /customers`, and `GET /admin/roles`.
- A `PORTAL` user cannot be assigned an internal role through `POST /admin/users/{id}/roles`.
- `POST /api/platform/tenants` without platform-admin credentials returns **401**.
- Every mutating endpoint refuses an unauthenticated request with **401**, iterating the endpoint list explicitly rather than sampling.

- [ ] **Step 9: Secure the platform endpoints**

Replace the `permitAll` on `/api/platform/**` from Task 15 with authentication against `platform_admin` — HTTP Basic is sufficient for sub-project 1, since these endpoints are operated by hand:

```java
                .requestMatchers("/api/platform/**").hasRole("PLATFORM_ADMIN")
```

Back it with a `UserDetailsService` reading `platform_admin`, and add `.httpBasic(Customizer.withDefaults())`.

- [ ] **Step 10: Run the full security suite**

Run: `cd backend && ./gradlew test --tests "co.ara.onboarding.security.*"`
Expected: PASS — all eight classes.

- [ ] **Step 11: Run everything**

Run: `cd backend && ./gradlew test`
Expected: PASS. This is the definition-of-done gate for the backend.

- [ ] **Step 12: Commit**

```bash
git add backend/
git commit -m "test: add the eight required negative security tests"
```

---

### Task 23: Frontend scaffold, theming, and generated types

**Files:**
- Create: `frontend/package.json`, `tsconfig.json`, `next.config.ts`, `tailwind.config.ts`
- Create: `frontend/src/app/globals.css`
- Create: `frontend/src/lib/i18n/messages/en.json`
- Create: `frontend/src/lib/i18n/index.ts`
- Create: `frontend/src/components/ThemeProvider.tsx`
- Test: `frontend/src/lib/i18n/i18n.test.ts`

**Interfaces:**
- Consumes: the backend OpenAPI document from Task 19.
- Produces:
  - `t(key: string, params?: Record<string, string>): string` — the translation function every user-facing string goes through.
  - `frontend/src/lib/api/generated.ts` — types generated by `openapi-typescript`.
  - `npm run generate:api` — regenerates types from a running backend.

- [ ] **Step 1: Scaffold**

```bash
npx create-next-app@latest frontend --typescript --tailwind --app --eslint --src-dir --no-import-alias
cd frontend
npm install @tanstack/react-query next-themes zod
npm install -D openapi-typescript vitest @testing-library/react @playwright/test
npx shadcn@latest init
npx shadcn@latest add button input label card table dialog dropdown-menu badge sonner
```

- [ ] **Step 2: Enable TypeScript strict mode**

In `tsconfig.json` set `"strict": true`, `"noUncheckedIndexedAccess": true`, `"noImplicitOverride": true`. These catch a class of bug that is otherwise found at runtime, and the cost is lowest at the start of a project.

- [ ] **Step 3: Write the failing i18n test**

`frontend/src/lib/i18n/i18n.test.ts`:

```typescript
import { describe, expect, it } from "vitest";
import { t } from "./index";

describe("t", () => {
  it("resolves a known key", () => {
    expect(t("auth.login.title")).toBe("Sign in");
  });

  it("interpolates parameters", () => {
    expect(t("customer.deactivate.confirm", { name: "Acme" }))
      .toContain("Acme");
  });

  it("returns the key itself when missing, so gaps are visible rather than silent", () => {
    expect(t("does.not.exist")).toBe("does.not.exist");
  });
});
```

- [ ] **Step 4: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/i18n`
Expected: FAIL — module not found.

- [ ] **Step 5: Implement the translation layer**

`frontend/src/lib/i18n/messages/en.json`:

```json
{
  "auth.login.title": "Sign in",
  "auth.login.email": "Email",
  "auth.login.password": "Password",
  "auth.login.submit": "Sign in",
  "auth.login.error": "Invalid email or password",
  "auth.login.lockedOut": "Too many attempts. Try again later.",
  "auth.activate.title": "Activate your account",
  "auth.reset.title": "Reset your password",
  "nav.dashboard": "Dashboard",
  "nav.customers": "Customers",
  "nav.admin": "Administration",
  "customer.list.title": "Customers",
  "customer.list.empty": "No customers yet",
  "customer.create.title": "New customer",
  "customer.deactivate.confirm": "Deactivate {name}? This cannot be undone.",
  "admin.users.title": "Users",
  "admin.roles.title": "Roles",
  "admin.departments.title": "Departments",
  "admin.teams.title": "Teams",
  "common.save": "Save",
  "common.cancel": "Cancel",
  "common.search": "Search"
}
```

```typescript
import en from "./messages/en.json";

const messages: Record<string, string> = en;

/**
 * English only at launch, but every user-facing string goes through here from
 * day one — retrofitting i18n across nine modules is a punishing refactor.
 */
export function t(key: string, params?: Record<string, string>): string {
  const template = messages[key];
  if (template === undefined) return key;
  if (!params) return template;
  return Object.entries(params).reduce(
    (acc, [name, value]) => acc.replaceAll(`{${name}}`, value),
    template,
  );
}
```

- [ ] **Step 6: Implement theming**

Use `next-themes` with `attribute="class"` and `defaultTheme="system"`. Define light and dark CSS custom properties in `globals.css` following the shadcn convention. Verify contrast meets WCAG AA for body text and interactive elements.

- [ ] **Step 7: Add the type generation script**

In `package.json`:

```json
"scripts": {
  "generate:api": "openapi-typescript http://localhost:8080/v3/api-docs -o src/lib/api/generated.ts"
}
```

Run it with the backend running. Commit the generated file so the frontend builds without a live backend, and regenerate whenever a backend contract changes — a stale file then surfaces as a compile error, which is the point.

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/lib/i18n`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add frontend/
git commit -m "chore: scaffold Next.js frontend with theming and i18n layer"
```

---

### Task 24: API client, auth provider, and route protection

The access token lives in memory only. This task is where that guarantee is either kept or quietly broken.

**Files:**
- Create: `frontend/src/lib/api/client.ts`
- Create: `frontend/src/lib/auth/AuthProvider.tsx`
- Create: `frontend/src/lib/auth/useAuth.ts`
- Create: `frontend/src/lib/auth/useHasPermission.ts`
- Create: `frontend/src/app/(app)/layout.tsx`
- Test: `frontend/src/lib/api/client.test.ts`

**Interfaces:**
- Consumes: `/api/t/{slug}/auth/login`, `/auth/refresh`, `/me`.
- Produces:
  - `apiFetch<T>(path: string, init?: RequestInit): Promise<T>` — prefixes `/api/t/{slug}`, attaches the bearer token, refreshes once on 401 and retries.
  - `useAuth()` → `{ user, permissions, login, logout, isLoading }`.
  - `useHasPermission(key: string, scope?: Scope)` → `boolean`.

- [ ] **Step 1: Write the failing test**

`frontend/src/lib/api/client.test.ts`:

```typescript
import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch, __setAccessToken, __getAccessToken } from "./client";

describe("apiFetch", () => {
  beforeEach(() => {
    __setAccessToken("initial-token");
    vi.restoreAllMocks();
  });

  it("attaches the bearer token", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), { status: 200 }));
    vi.stubGlobal("fetch", fetchSpy);

    await apiFetch("/customers");

    const headers = new Headers(fetchSpy.mock.calls[0][1].headers);
    expect(headers.get("Authorization")).toBe("Bearer initial-token");
  });

  it("refreshes once on 401 and retries the original request", async () => {
    const fetchSpy = vi.fn()
      .mockResolvedValueOnce(new Response("", { status: 401 }))
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ accessToken: "fresh-token", expiresInSeconds: 900 }),
        { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), { status: 200 }));
    vi.stubGlobal("fetch", fetchSpy);

    await apiFetch("/customers");

    expect(fetchSpy).toHaveBeenCalledTimes(3);
    expect(__getAccessToken()).toBe("fresh-token");
  });

  it("does not retry more than once", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(new Response("", { status: 401 }));
    vi.stubGlobal("fetch", fetchSpy);

    await expect(apiFetch("/customers")).rejects.toThrow();
    // original + refresh attempt + one retry, then stop
    expect(fetchSpy.mock.calls.length).toBeLessThanOrEqual(3);
  });

  it("never writes the token to browser storage", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    __setAccessToken("secret-token");
    expect(setItem).not.toHaveBeenCalled();
  });
});
```

The last test is the one that matters. It is easy to "fix" a refresh bug by persisting the token, which silently discards the whole reason for choosing this session model.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/api`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the client**

```typescript
// The access token lives ONLY in this module-scoped variable.
// Never localStorage, never sessionStorage, never a cookie (spec 7.2).
let accessToken: string | null = null;
let tenantSlug = "";
let refreshInFlight: Promise<boolean> | null = null;

export function __setAccessToken(token: string | null) { accessToken = token; }
export function __getAccessToken() { return accessToken; }
export function setTenantSlug(slug: string) { tenantSlug = slug; }

async function refreshAccessToken(): Promise<boolean> {
  // Collapse concurrent refreshes: parallel 401s must not each rotate the
  // refresh token, which reuse detection would read as theft.
  if (refreshInFlight) return refreshInFlight;

  refreshInFlight = (async () => {
    const response = await fetch(`/api/t/${tenantSlug}/auth/refresh`, {
      method: "POST",
      credentials: "include",
    });
    if (!response.ok) { accessToken = null; return false; }
    const body = await response.json();
    accessToken = body.accessToken;
    return true;
  })().finally(() => { refreshInFlight = null; });

  return refreshInFlight;
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const send = () => fetch(`/api/t/${tenantSlug}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(init.headers ?? {}),
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
  });

  let response = await send();

  if (response.status === 401) {
    const refreshed = await refreshAccessToken();
    if (refreshed) response = await send();
  }

  if (!response.ok) {
    throw new ApiError(response.status, await response.text());
  }
  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>);
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}
```

- [ ] **Step 4: Implement `AuthProvider` and the hooks**

`AuthProvider` holds `user` and `permissions` (from `/me`), exposes `login` and `logout`, and on mount attempts a silent refresh so a page reload does not log the user out — the refresh cookie survives, the in-memory token does not.

```typescript
export function useHasPermission(key: string, scope?: Scope): boolean {
  const { permissions } = useAuth();
  const scopes = permissions[key];
  if (!scopes || scopes.length === 0) return false;
  if (!scope) return true;
  return scopes.includes("ALL") || scopes.includes(scope);
}
```

Add a comment at the top of the file: **this hook controls UI affordances only; the server is the sole authority (spec §10.3).**

- [ ] **Step 5: Implement route protection**

`src/app/(app)/layout.tsx` reads `useAuth()`, renders a loading state while the silent refresh resolves, and redirects to `/t/{slug}/login` when unauthenticated.

- [ ] **Step 6: Run it to verify it passes**

Run: `cd frontend && npx vitest run src/lib/api`
Expected: PASS — all four tests.

- [ ] **Step 7: Commit**

```bash
git add frontend/
git commit -m "feat: add API client with in-memory token and silent refresh"
```

---

### Task 25: Public pages — login, activation, password reset

**Files:**
- Create: `frontend/src/app/(public)/t/[slug]/login/page.tsx`
- Create: `frontend/src/app/(public)/t/[slug]/activate/page.tsx`
- Create: `frontend/src/app/(public)/t/[slug]/reset-password/page.tsx`
- Create: `frontend/src/components/auth/AuthCard.tsx`

**Interfaces:**
- Consumes: `useAuth`, `apiFetch`, `t`.
- Produces: three routed pages. All use `AuthCard` for consistent layout.

- [ ] **Step 1: Build the login page**

Email and password fields, submit, error handling for 401 (`auth.login.error`) and 429 (`auth.login.lockedOut`). On success, redirect to `/t/{slug}/dashboard`.

Every string goes through `t()`. No hardcoded copy.

- [ ] **Step 2: Build the activation page**

Reads `?token=` from the query string, prompts for a password with a minimum of 12 characters and a confirmation field, and posts to `/auth/activate`. On success, redirects to login with a success toast.

- [ ] **Step 3: Build the password reset pages**

Request form posting to `/auth/password-reset/request`. It always shows the same confirmation message regardless of whether the address exists — the UI must not undo the backend's deliberate non-enumeration. Confirm form reads `?token=` and posts to `/auth/password-reset/confirm`.

- [ ] **Step 4: Verify manually**

Start both applications, provision a tenant via `POST /api/platform/tenants` with the platform-admin credentials, and confirm you can log in as the seeded administrator after activating through the emailed link. In development the link is printed by `LoggingEmailSender` in the backend log.

- [ ] **Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: add login, activation and password reset pages"
```

---

### Task 26: Application shell

**Files:**
- Create: `frontend/src/components/shell/Sidebar.tsx`
- Create: `frontend/src/components/shell/TopBar.tsx`
- Create: `frontend/src/components/shell/ThemeToggle.tsx`
- Modify: `frontend/src/app/(app)/layout.tsx`
- Create: `frontend/src/app/(app)/t/[slug]/dashboard/page.tsx`

**Interfaces:**
- Consumes: `useAuth`, `useHasPermission`, `t`.
- Produces: the authenticated shell wrapping every application page.

- [ ] **Step 1: Build the sidebar**

Navigation entries filtered by permission: Customers requires `customer.view`; Administration requires `role.view` or `user.view`. Collapses to a sheet on mobile.

- [ ] **Step 2: Build the top bar**

Tenant name, user menu with the user's name and a sign-out action, and the theme toggle.

- [ ] **Step 3: Build the dashboard placeholder**

An empty state naming what arrives in sub-project 8, so it reads as deliberate rather than unfinished.

- [ ] **Step 4: Verify responsiveness and contrast**

Check at 375px, 768px, and 1440px in both themes. Verify AA contrast for body text and interactive elements.

- [ ] **Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: add authenticated application shell"
```

---

### Task 27: Customer list and detail

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/customers/page.tsx`
- Create: `frontend/src/app/(app)/t/[slug]/customers/[id]/page.tsx`
- Create: `frontend/src/components/customers/CustomerTable.tsx`
- Create: `frontend/src/components/customers/CustomerForm.tsx`
- Create: `frontend/src/components/customers/ContactList.tsx`
- Create: `frontend/src/lib/api/customers.ts`

**Interfaces:**
- Consumes: `apiFetch`, `useHasPermission`, TanStack Query.
- Produces: `useCustomers(params)`, `useCustomer(id)`, `useCreateCustomer()`, `useUpdateCustomer()`, `useDeactivateCustomer()`, `useSendInvitation()`.

- [ ] **Step 1: Build the list page**

Search, status filter, pagination. "New customer" is rendered only when `useHasPermission("customer.create")`. Empty state uses `customer.list.empty`.

- [ ] **Step 2: Build the detail page**

Customer summary, editable when `customer.edit` is held, contact list, and a "Send invitation" action per contact gated on `invitation.send`.

- [ ] **Step 3: Handle 404 correctly**

A 404 from the API renders "Not found" — never "You don't have access to this record." The backend deliberately does not distinguish the two, and the UI must not reintroduce the distinction the 404 exists to hide.

- [ ] **Step 4: Wire deactivation**

Confirmation dialog using `customer.deactivate.confirm` with the customer name interpolated. No delete action exists anywhere in the UI.

- [ ] **Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: add customer list and detail pages"
```

---

### Task 28: Administration screens and end-to-end tests

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/admin/users/page.tsx`
- Create: `frontend/src/app/(app)/t/[slug]/admin/roles/page.tsx`
- Create: `frontend/src/app/(app)/t/[slug]/admin/org/page.tsx`
- Create: `frontend/src/components/admin/RoleEditor.tsx`
- Create: `frontend/e2e/auth.spec.ts`
- Create: `frontend/e2e/activation.spec.ts`
- Create: `frontend/e2e/refresh-reuse.spec.ts`
- Create: `frontend/e2e/customers.spec.ts`
- Create: `frontend/playwright.config.ts`

**Interfaces:**
- Consumes: `/admin/*` endpoints from Task 21, `/admin/permissions` for the catalog.
- Produces: Playwright coverage of login, activation, refresh, and reuse detection.

- [ ] **Step 1: Build the role editor**

This is the most intricate screen in the sub-project. It lists the permission catalog grouped by category, and for each permission offers **only the scopes that permission allows** — read from `/admin/permissions`, never hardcoded. A permission that is `ALL`-only shows a single option, not a disabled dropdown of four.

- [ ] **Step 2: Build the user and org screens**

User list with role assignment, invite action, and deactivate. Departments and teams as simple CRUD.

- [ ] **Step 3: Write the end-to-end tests**

Spec §11.4 names four flows, all of which must be covered: login, invitation activation, token refresh, and refresh-token reuse detection.

`e2e/auth.spec.ts`:
- Log in with valid credentials and reach the dashboard.
- Invalid credentials show the error and stay on the login page.
- **Reload the page while signed in and remain signed in** — this proves silent refresh works, and it is the flow most likely to regress.
- Sign out, then confirm going back in browser history does not restore an authenticated view.

`e2e/activation.spec.ts`:
- Provision a tenant, create a customer and contact, and send an invitation through the API.
- Read the activation token from the backend's development log output (`LoggingEmailSender`), or expose it through a test-only endpoint enabled solely under the `test` profile.
- Complete the activation form, then sign in as the resulting `PORTAL` user.
- Confirm the same activation link fails on a second attempt.

`e2e/refresh-reuse.spec.ts`:
- Sign in, then capture the `refresh_token` cookie value via the browser context.
- Force a refresh so the token rotates.
- Replay the captured (now-retired) cookie against `/auth/refresh` directly.
- Assert the replay is rejected **and** that the legitimate session is also terminated — the whole family is revoked. Confirm the user is bounced to login on their next navigation.

That last assertion is the one worth writing carefully. Rejecting the stolen token is the easy half; killing the legitimate session is what makes reuse detection meaningful, and it is easy to implement in a way that quietly does not.

`e2e/customers.spec.ts`:
- Create a customer and see it in the list.
- Add a contact and send an invitation.
- A user without `customer.create` does not see the "New customer" button **and** receives an error if the endpoint is called directly from the page context.

- [ ] **Step 4: Run the end-to-end suite**

Run: `cd frontend && npx playwright test`
Expected: PASS. Both applications must be running, against a database seeded by the platform provisioning endpoint.

- [ ] **Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: add administration screens and end-to-end tests"
```

---

### Task 29: CLAUDE.md

The handoff artifact. After this, a cold session in this repository can continue without any conversation history.

**Files:**
- Create: `CLAUDE.md` (repository root)
- Modify: `docs/superpowers/plans/2026-08-12-foundation-and-tenancy.md` (mark remaining boxes)

**Interfaces:**
- Consumes: everything built in Tasks 1–28.
- Produces: the conventions file loaded automatically in every future session.

- [ ] **Step 1: Verify the whole suite is green before documenting it**

```bash
cd backend && ./gradlew test
cd ../frontend && npx vitest run && npx playwright test
```

Do not write CLAUDE.md describing invariants that are not actually passing. If anything fails, fix it first.

- [ ] **Step 2: Write CLAUDE.md**

It must cover, concretely:

**Project shape** — monorepo, `backend/` and `frontend/`, what sub-project 1 delivered, and the sub-project sequence with a pointer to `docs/superpowers/specs/`.

**Commands** — how to run each application, run the backend tests, run frontend unit and end-to-end tests, provision a tenant locally, and find the activation link in the development log.

**Non-negotiable invariants**, each with the reason:
- Every tenant-owned table needs `tenant_id`, an RLS policy, and `FORCE ROW LEVEL SECURITY`, created in the same migration as the table.
- The application connects as `onboarding_app`; migrations run as the owner. Never connect the app as a superuser — RLS would not constrain it.
- Reads of tenant data go through `AuthorizedQuery`. Never call repository finders directly from a service.
- Every public service method carries `@RequirePermission`.
- Out-of-scope records return 404, never 403.
- No deny grants. Absence of a grant is the denial.
- Permissions are never embedded in tokens, and never cached across requests.
- New resource types must register a `ResourceAuthorizationDescriptor` — the application will refuse to start otherwise.
- `ASSIGNED` means a personal relationship; team-mediated access is `TEAM`.
- No hard deletes of business records; deactivate instead.
- The audit table is append-only at the database level.
- Migrations are forward-only.

**Conventions** — package layout, one domain module per sub-project, TDD with security tests first, Conventional Commits, all user-facing strings through `t()`.

**Where the guards live** — `RlsCoverageTest`, `AuthorizationCoverageTest`, `ModuleBoundaryTest`, `DescriptorRegistry.validate()`, and the eight tests in `security/`. State plainly that these are not to be weakened to make a change pass.

**What sub-project 2 inherits** — the descriptor seam, the `RelationshipType` vocabulary that cases and milestones will extend, and the `timeline_visible` flag on `audit_event` that the Activity Timeline reads.

- [ ] **Step 3: Verify CLAUDE.md against reality**

Run every command listed in the file and confirm each works as written. A CLAUDE.md with a stale command is worse than none — it gets trusted.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/
git commit -m "docs: add CLAUDE.md capturing durable project conventions"
```

- [ ] **Step 5: Final verification**

```bash
cd backend && ./gradlew test
cd ../frontend && npx vitest run && npx playwright test
```

Confirm the definition of done in spec §12: tenant provisioning seeds twelve roles; a tenant administrator manages users, roles, departments and teams; customers and contacts can be created and invited; a contact activates and logs in as a `PORTAL` user; every action is audited; and all eight negative security tests plus the structural guards pass.

---

## Notes for the Executor

**If a structural guard fails, fix the code, not the guard.** `RlsCoverageTest`, `AuthorizationCoverageTest`, `ModuleBoundaryTest`, and `DescriptorRegistry.validate()` exist precisely to fail when something is missed. Adding an allowlist entry or an exclusion to make a build green defeats the entire isolation design, and the failure mode — silent cross-tenant data exposure — is the one this product cannot survive.

**Docker must be running** for any backend test; Testcontainers needs it.

**Migrations are forward-only.** If a committed migration is wrong, add a new `V*` file that corrects it.

**Tasks 20 and 21 are the pattern for sub-projects 2–9.** Every later domain module repeats the same shape: entity with `tenant_id`, migration with `enable_tenant_rls`, a `ResourceAuthorizationDescriptor`, a service where every public method is gated and every read goes through `AuthorizedQuery`, and a thin controller.







