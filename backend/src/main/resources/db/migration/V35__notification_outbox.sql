-- Sub-project 6B, spec §4.1, §4.5, §4.6 and plan amendments 6-7. notification widens to every
-- type; email_outbox becomes the single queue for every notification-class email. Neither
-- notification nor email_outbox grants DELETE: each row is the only record that a message
-- was raised or sent.

ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check CHECK (type IN (
    'ESCALATION','TASK_ASSIGNED','TASK_OVERDUE','NEW_CUSTOMER','MILESTONE_COMPLETED','STAGE_CHANGED',
    'DOCUMENT_REQUESTED','DOCUMENT_UPLOADED','DOCUMENT_DECIDED','AGREEMENT_STATUS','NEW_COMMENT',
    'WORKFLOW_PUBLISHED','RISK_CHANGED','DEADLINE_APPROACHING','EXPIRY_RENEWAL'));

ALTER TABLE notification ADD COLUMN subject_type text    NULL;
ALTER TABLE notification ADD COLUMN subject_id   uuid    NULL;
ALTER TABLE notification ADD COLUMN in_app       boolean NOT NULL DEFAULT true;
ALTER TABLE notification ADD COLUMN email_state  text    NOT NULL DEFAULT 'NONE'
    CHECK (email_state IN ('NONE','QUEUED','DIGEST_PENDING','DIGESTED'));
ALTER TABLE notification ADD COLUMN tone         text    NOT NULL DEFAULT 'INFO'
    CHECK (tone IN ('RISK','WARN','OK','INFO'));
ALTER TABLE notification ADD COLUMN dedupe_key   text    NULL CHECK (length(dedupe_key) <= 200);

-- Every existing row is a sub-project 6 escalation about a case. Runs as the Flyway owner, so
-- RLS does not filter it (the V28 precedent).
UPDATE notification SET subject_type = 'case', subject_id = COALESCE(case_id, escalation_id),
    email_state = CASE WHEN emailed_at IS NULL THEN 'QUEUED' ELSE 'NONE' END, tone = 'RISK';
ALTER TABLE notification ALTER COLUMN subject_type SET NOT NULL;
ALTER TABLE notification ALTER COLUMN subject_id   SET NOT NULL;

-- Invariant 7: the database, not the sweep, makes a reminder idempotent. NULLs are distinct,
-- so event-driven rows (no key) are unconstrained.
ALTER TABLE notification ADD CONSTRAINT notification_once_per_dedupe_key
    UNIQUE (tenant_id, recipient_user_id, dedupe_key);
DROP INDEX notification_unsent_idx;
CREATE INDEX notification_inbox_idx  ON notification (tenant_id, recipient_user_id, id DESC) WHERE in_app;
CREATE INDEX notification_unread_idx ON notification (tenant_id, recipient_user_id) WHERE in_app AND read_at IS NULL;
CREATE INDEX notification_digest_idx ON notification (tenant_id, recipient_user_id) WHERE email_state = 'DIGEST_PENDING';

CREATE TABLE email_outbox (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenant(id),
    kind                 text        NOT NULL CHECK (kind IN ('NOTIFICATION','DIGEST','CUSTOMER_REMINDER')),
    to_address           text        NOT NULL,
    recipient_user_id    uuid        NULL REFERENCES app_user(id),
    contact_id           uuid        NULL REFERENCES customer_contact(id),
    notification_id      uuid        NULL REFERENCES notification(id),
    document_request_id  uuid        NULL REFERENCES document_request(id),
    subject              text        NOT NULL,
    body                 text        NOT NULL,
    link_path            text        NULL,       -- appended as an absolute link at send time (plan amendment 7)
    status               text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SENDING','SENT','FAILED','SKIPPED')),
    attempts             int         NOT NULL DEFAULT 0,
    next_attempt_at      timestamptz NOT NULL,
    lease_until          timestamptz NULL,
    last_error           text        NULL,
    sent_at              timestamptz NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT email_outbox_one_recipient_ck CHECK ((recipient_user_id IS NULL) <> (contact_id IS NULL))
);
CREATE INDEX email_outbox_due_idx ON email_outbox (next_attempt_at) WHERE status IN ('PENDING','SENDING');
SELECT enable_tenant_rls('email_outbox');
GRANT SELECT, INSERT, UPDATE ON email_outbox TO onboarding_app;

-- Which notifications one digest email carried. Append-only, the audit_event shape.
CREATE TABLE email_outbox_item (
    outbox_id        uuid        NOT NULL REFERENCES email_outbox(id),
    notification_id  uuid        NOT NULL REFERENCES notification(id),
    tenant_id        uuid        NOT NULL REFERENCES tenant(id),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (outbox_id, notification_id)
);
SELECT enable_tenant_rls('email_outbox_item');
GRANT SELECT, INSERT ON email_outbox_item TO onboarding_app;

-- Unsent sub-project 6 escalation emails move into the outbox, so the dispatcher (not the
-- retired SLA retry step) delivers them.
INSERT INTO email_outbox (id, tenant_id, kind, to_address, recipient_user_id, notification_id, subject,
                          body, link_path, status, attempts, next_attempt_at, created_at, updated_at)
SELECT gen_random_uuid(), n.tenant_id, 'NOTIFICATION', u.email, n.recipient_user_id, n.id, n.title,
       n.body, n.link_path, 'PENDING', 0, now(), now(), now()
  FROM notification n JOIN app_user u ON u.id = n.recipient_user_id
 WHERE n.emailed_at IS NULL;

ALTER TABLE sla_clock ADD COLUMN at_risk_alerted_at timestamptz NULL;
