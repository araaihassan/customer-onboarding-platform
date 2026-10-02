-- Sub-project 6, spec §4.1. One calendar and one SLA policy per tenant; holidays are
-- configuration, not business records.

CREATE TABLE business_calendar (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL UNIQUE REFERENCES tenant(id),
    name          text        NOT NULL DEFAULT 'Business calendar',
    timezone      text        NOT NULL DEFAULT 'UTC',
    working_days  smallint[]  NOT NULL DEFAULT '{1,2,3,4,5}',   -- ISO day-of-week, 1 = Monday
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT business_calendar_working_days_ck
        CHECK (cardinality(working_days) BETWEEN 1 AND 7 AND working_days <@ '{1,2,3,4,5,6,7}')
);
SELECT enable_tenant_rls('business_calendar');
GRANT SELECT, INSERT, UPDATE ON business_calendar TO onboarding_app;

CREATE TABLE business_holiday (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant(id),
    holiday_date  date        NOT NULL,
    name          text        NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT business_holiday_tenant_date_uq UNIQUE (tenant_id, holiday_date)
);
SELECT enable_tenant_rls('business_holiday');
-- DELETE is deliberate: a holiday is tenant configuration, not a business record, and removing
-- a mistaken one has to actually remove it (spec §4.1). Its removal is audited
-- (calendar.holiday_removed), which is the record that it existed.
GRANT SELECT, INSERT, UPDATE, DELETE ON business_holiday TO onboarding_app;

CREATE TABLE sla_policy (
    id                          uuid         PRIMARY KEY,
    tenant_id                   uuid         NOT NULL UNIQUE REFERENCES tenant(id),
    at_risk_days                numeric(4,1) NOT NULL DEFAULT 1.0 CHECK (at_risk_days >= 0),
    escalate_after_overdue_days int          NOT NULL DEFAULT 1 CHECK (escalate_after_overdue_days >= 1),
    created_at                  timestamptz  NOT NULL,
    updated_at                  timestamptz  NOT NULL
);
SELECT enable_tenant_rls('sla_policy');
GRANT SELECT, INSERT, UPDATE ON sla_policy TO onboarding_app;

-- Backfill every existing tenant. Runs as the Flyway owner (a superuser), so RLS does not
-- filter it -- the V15/V26 precedent. gen_random_uuid() is acceptable here only because these
-- are one-time backfill keys; application code always uses Uuid7.generate().
INSERT INTO business_calendar (id, tenant_id, created_at, updated_at)
    SELECT gen_random_uuid(), id, now(), now() FROM tenant;
INSERT INTO sla_policy (id, tenant_id, created_at, updated_at)
    SELECT gen_random_uuid(), id, now(), now() FROM tenant;
