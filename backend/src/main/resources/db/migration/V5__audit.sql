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
