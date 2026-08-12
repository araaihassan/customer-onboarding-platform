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

-- flyway_schema_history already exists by the time this migration runs (Flyway
-- creates it, owned by the migration-owner role, before applying V1). The
-- ALTER DEFAULT PRIVILEGES above only affects tables created after it runs, so
-- it does not retroactively cover this one. ApplicationContextTest reads this
-- table through the onboarding_app datasource, so grant it explicitly.
GRANT SELECT ON flyway_schema_history TO onboarding_app;

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
-- It appears on the RLS meta-test allowlist in Task 6.
GRANT SELECT, INSERT, UPDATE ON tenant TO onboarding_app;
