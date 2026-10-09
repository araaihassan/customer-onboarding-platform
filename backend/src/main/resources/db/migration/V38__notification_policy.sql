-- Sub-project 6B, spec §4.4. Lead times are configuration replaced as a set, so deadline_horizon
-- is the one 6B table that grants DELETE: removing a lead time has to actually remove it, and
-- notification_policy.updated is the record that it existed.
CREATE TABLE deadline_horizon (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant(id),
    kind        text        NOT NULL CHECK (kind IN ('TASK_DUE','MILESTONE_DUE','DOCUMENT_REQUEST_DUE',
                                                     'DOCUMENT_EXPIRY','AGREEMENT_EXPIRY','AGREEMENT_RENEWAL')),
    lead_days   int         NOT NULL CHECK (lead_days BETWEEN 1 AND 90),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT deadline_horizon_once UNIQUE (tenant_id, kind, lead_days)
);
SELECT enable_tenant_rls('deadline_horizon');
GRANT SELECT, INSERT, UPDATE, DELETE ON deadline_horizon TO onboarding_app;

CREATE TABLE notification_policy (
    id                         uuid        PRIMARY KEY,
    tenant_id                  uuid        NOT NULL UNIQUE REFERENCES tenant(id),
    auto_remind_enabled        boolean     NOT NULL DEFAULT false,   -- spec 1.2.1: off until a tenant decides
    auto_remind_interval_days  int         NOT NULL DEFAULT 3 CHECK (auto_remind_interval_days BETWEEN 1 AND 30),
    auto_remind_max            int         NOT NULL DEFAULT 3 CHECK (auto_remind_max BETWEEN 1 AND 10),
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL
);
SELECT enable_tenant_rls('notification_policy');
GRANT SELECT, INSERT, UPDATE ON notification_policy TO onboarding_app;

-- Backfill (Flyway owner; the V28 precedent).
INSERT INTO notification_policy (id, tenant_id, created_at, updated_at)
    SELECT gen_random_uuid(), id, now(), now() FROM tenant;
INSERT INTO deadline_horizon (id, tenant_id, kind, lead_days, created_at, updated_at)
    SELECT gen_random_uuid(), t.id, k.kind, k.lead_days, now(), now()
      FROM tenant t CROSS JOIN (VALUES
           ('TASK_DUE', 2), ('MILESTONE_DUE', 2), ('DOCUMENT_REQUEST_DUE', 2),
           ('DOCUMENT_EXPIRY', 30), ('DOCUMENT_EXPIRY', 14), ('DOCUMENT_EXPIRY', 7),
           ('AGREEMENT_EXPIRY', 30), ('AGREEMENT_EXPIRY', 14), ('AGREEMENT_EXPIRY', 7),
           ('AGREEMENT_RENEWAL', 30), ('AGREEMENT_RENEWAL', 14), ('AGREEMENT_RENEWAL', 7)) AS k(kind, lead_days);
