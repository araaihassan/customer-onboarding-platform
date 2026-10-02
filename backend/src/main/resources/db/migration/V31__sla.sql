-- Sub-project 6, spec §4.4-§4.6. Clocks and pause intervals are written synchronously by the
-- ports; elapsed time is derived on read and never stored (invariant 4). None of these tables
-- grants DELETE: a clock, a pause, an escalation and a notification are each the only record
-- that the thing happened.

CREATE TABLE sla_clock (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant(id),
    case_id         uuid        NOT NULL REFERENCES onboarding_case(id),
    stage_id        uuid        NOT NULL REFERENCES stage(id),
    target_days     int         NOT NULL CHECK (target_days > 0),
    pause_eligible  boolean     NOT NULL,
    started_at      timestamptz NOT NULL,
    stopped_at      timestamptz NULL,
    outcome         text        NULL CHECK (outcome IN ('MET','BREACHED')),
    breached_at     timestamptz NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT sla_clock_stopped_has_outcome_ck CHECK ((stopped_at IS NULL) = (outcome IS NULL))
);
CREATE UNIQUE INDEX sla_clock_one_open_per_case ON sla_clock (case_id) WHERE stopped_at IS NULL;
CREATE INDEX sla_clock_case_idx ON sla_clock (case_id, started_at DESC);
SELECT enable_tenant_rls('sla_clock');
GRANT SELECT, INSERT, UPDATE ON sla_clock TO onboarding_app;

CREATE TABLE sla_pause (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant(id),
    clock_id    uuid        NOT NULL REFERENCES sla_clock(id),
    reason      text        NOT NULL CHECK (reason IN ('CASE_HOLD','OPEN_DOCUMENT_REQUEST')),
    started_at  timestamptz NOT NULL,
    ended_at    timestamptz NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);
CREATE UNIQUE INDEX sla_pause_one_open_per_reason ON sla_pause (clock_id, reason) WHERE ended_at IS NULL;
SELECT enable_tenant_rls('sla_pause');
GRANT SELECT, INSERT, UPDATE ON sla_pause TO onboarding_app;

CREATE TABLE escalation (
    id                      uuid        PRIMARY KEY,
    tenant_id               uuid        NOT NULL REFERENCES tenant(id),
    subject_type            text        NOT NULL CHECK (subject_type IN ('TASK','MILESTONE','SLA_CLOCK')),
    subject_id              uuid        NOT NULL,
    case_id                 uuid        NOT NULL REFERENCES onboarding_case(id),
    late_user_id            uuid        NULL REFERENCES app_user(id),
    route                   text        NOT NULL CHECK (route IN ('MANAGER','DEPARTMENT_HEAD','ADMINISTRATORS')),
    escalated_to_user_id    uuid        NULL REFERENCES app_user(id),
    due_date_at_escalation  date        NOT NULL,
    overdue_days            int         NOT NULL,
    escalated_at            timestamptz NOT NULL,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    -- Invariant 5: the database, not the sweep, is what makes escalation idempotent.
    CONSTRAINT escalation_once_per_subject_and_due_date
        UNIQUE (tenant_id, subject_type, subject_id, due_date_at_escalation)
);
CREATE INDEX escalation_case_idx ON escalation (case_id, escalated_at DESC);
SELECT enable_tenant_rls('escalation');
GRANT SELECT, INSERT ON escalation TO onboarding_app;

CREATE TABLE notification (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenant(id),
    recipient_user_id  uuid        NOT NULL REFERENCES app_user(id),
    type               text        NOT NULL CHECK (type IN ('ESCALATION')),   -- 6B widens this by migration
    title              text        NOT NULL,
    body               text        NOT NULL,
    link_path          text        NOT NULL,
    case_id            uuid        NULL REFERENCES onboarding_case(id),
    escalation_id      uuid        NULL REFERENCES escalation(id),
    read_at            timestamptz NULL,
    emailed_at         timestamptz NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);
CREATE INDEX notification_unsent_idx ON notification (type) WHERE emailed_at IS NULL;
SELECT enable_tenant_rls('notification');
GRANT SELECT, INSERT, UPDATE ON notification TO onboarding_app;

ALTER TABLE document_request ADD COLUMN reminders_sent   int         NOT NULL DEFAULT 0;
ALTER TABLE document_request ADD COLUMN last_reminded_at timestamptz NULL;
