-- Global catalog mirror. Deliberately NOT tenant-scoped: the catalog is
-- identical for every tenant, and tenants configure roles rather than
-- inventing permissions (spec 6.2). It is therefore on RlsCoverageTest's
-- reviewed NOT_TENANT_SCOPED allowlist -- one of the four entries that exist,
-- which is why that list is an explicit, deliberate act rather than a
-- convenience.
--
-- The authoritative catalog is PermissionCatalog in code. This table exists so
-- the database can join against permission keys and so operators can inspect
-- them; nothing reads it to decide authority.
CREATE TABLE permission (
    key             varchar(64) PRIMARY KEY,
    category        varchar(64) NOT NULL,
    resource_type   varchar(64),          -- NULL for ALL-only permissions
    description     text        NOT NULL,
    allowed_scopes  varchar(255) NOT NULL
);

-- SELECT, INSERT, UPDATE only. PermissionSyncRunner upserts on every startup,
-- so it needs INSERT and UPDATE, but no DELETE: orphaned permissions are logged
-- and ignored, never auto-removed, so a mistaken catalog removal stays
-- revertible without data loss (spec 6.2).
--
-- The grant must be explicit. V5_1 revoked the schema-wide ALTER DEFAULT
-- PRIVILEGES grant, so a new table starts with no privileges for onboarding_app
-- at all.
GRANT SELECT, INSERT, UPDATE ON permission TO onboarding_app;
