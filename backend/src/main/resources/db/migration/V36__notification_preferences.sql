-- Sub-project 6B, spec 4.2. A preference row exists only where a user overrides a default;
-- ESCALATION can never be stored (QA Q10: it cannot be opted out of).
CREATE TABLE notification_preference (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant(id),
    user_id         uuid        NOT NULL REFERENCES app_user(id),
    type            text        NOT NULL,
    in_app_enabled  boolean     NOT NULL,
    email_enabled   boolean     NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT notification_preference_type_check CHECK (type IN (
        'TASK_ASSIGNED','TASK_OVERDUE','NEW_CUSTOMER','MILESTONE_COMPLETED','STAGE_CHANGED',
        'DOCUMENT_REQUESTED','DOCUMENT_UPLOADED','DOCUMENT_DECIDED','AGREEMENT_STATUS','NEW_COMMENT',
        'WORKFLOW_PUBLISHED','RISK_CHANGED','DEADLINE_APPROACHING','EXPIRY_RENEWAL')),
    CONSTRAINT notification_preference_once UNIQUE (tenant_id, user_id, type)
);
SELECT enable_tenant_rls('notification_preference');
GRANT SELECT, INSERT, UPDATE ON notification_preference TO onboarding_app;

CREATE TABLE notification_settings (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant(id),
    user_id         uuid        NOT NULL REFERENCES app_user(id),
    email_cadence   text        NOT NULL DEFAULT 'IMMEDIATE' CHECK (email_cadence IN ('IMMEDIATE','DAILY','WEEKLY')),
    last_digest_at  timestamptz NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT notification_settings_one_per_user UNIQUE (tenant_id, user_id)
);
SELECT enable_tenant_rls('notification_settings');
GRANT SELECT, INSERT, UPDATE ON notification_settings TO onboarding_app;
