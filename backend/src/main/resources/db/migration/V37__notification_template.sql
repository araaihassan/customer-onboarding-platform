-- Sub-project 6B, spec §4.3. A template is referenced by key from stage.notification_template_key,
-- so the key is immutable and templates are deactivated, never deleted.
CREATE TABLE notification_template (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenant(id),
    key              text        NOT NULL CHECK (key ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'),
    name             text        NOT NULL CHECK (length(name) <= 120),
    entered_subject  text        NOT NULL CHECK (length(entered_subject) <= 200),
    entered_body     text        NOT NULL CHECK (length(entered_body) <= 2000),
    exited_subject   text        NULL CHECK (length(exited_subject) <= 200),
    exited_body      text        NULL CHECK (length(exited_body) <= 2000),
    active           boolean     NOT NULL DEFAULT true,
    created_by       uuid        NULL REFERENCES app_user(id),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT notification_template_key_uq UNIQUE (tenant_id, key),
    CONSTRAINT notification_template_exit_pair_ck CHECK ((exited_subject IS NULL) = (exited_body IS NULL))
);
SELECT enable_tenant_rls('notification_template');
GRANT SELECT, INSERT, UPDATE ON notification_template TO onboarding_app;

-- notification.manage for every existing tenant's Administrator role. Sub-project 6 did not backfill
-- calendar.manage (only newly provisioned tenants get it), so this is a deliberate improvement: the
-- permission row is upserted first because role_grant has an FK to it and PermissionSyncRunner only
-- runs after migrations. Flyway runs as the owner, which bypasses FORCE ROW LEVEL SECURITY (as V15 does).
INSERT INTO permission (key, category, resource_type, description, allowed_scopes)
VALUES ('notification.manage', 'tenant', NULL,
        'Manage notification templates, deadline horizons and automatic reminders', 'ALL')
ON CONFLICT (key) DO NOTHING;

INSERT INTO role_grant (id, tenant_id, role_id, permission_key, scope, created_at, updated_at)
SELECT gen_random_uuid(), r.tenant_id, r.id, 'notification.manage', 'ALL', now(), now()
FROM role r
WHERE r.name = 'Administrator' AND r.system_template
ON CONFLICT (role_id, permission_key) DO NOTHING;
