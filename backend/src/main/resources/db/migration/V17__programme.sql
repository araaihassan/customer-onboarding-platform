-- Sub-project 3A, Task 9: a programme groups a customer's parallel journeys (QA Q20).
-- It has NO lifecycle -- no hold, no approval, no engine. `status` exists only
-- because business records are deactivated and never deleted, and DELETE is revoked
-- at the database layer.

CREATE TABLE programme (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    customer_id          uuid NOT NULL REFERENCES customer(id),
    name                 text NOT NULL,
    description          text,
    -- All three are what ProgrammeDescriptor's DEPARTMENT and TEAM predicates read.
    -- Without them both collapse to cb.disjunction() and only ALL-scoped holders
    -- ever see a programme.
    owner_user_id        uuid     NULL REFERENCES app_user(id),
    owning_department_id uuid     NULL REFERENCES department(id),
    owning_team_id       uuid     NULL REFERENCES team(id),
    status               text NOT NULL,
    created_by           uuid     NULL REFERENCES app_user(id),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT programme_status_ck CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT programme_name_ck   CHECK (length(btrim(name)) > 0)
);
CREATE INDEX programme_tenant_customer_idx ON programme (tenant_id, customer_id);

CREATE TABLE programme_participant (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    programme_id      uuid NOT NULL REFERENCES programme(id),
    user_id           uuid NOT NULL REFERENCES app_user(id),
    relationship_type text NOT NULL,
    status            text NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT programme_participant_status_ck CHECK (status IN ('ACTIVE','REMOVED')),
    CONSTRAINT programme_participant_rel_ck CHECK (
        relationship_type IN ('OWNER','ASSIGNEE','PARTICIPANT','APPROVER','CREATOR'))
);
CREATE UNIQUE INDEX programme_participant_uq
    ON programme_participant (programme_id, user_id);

CREATE TABLE programme_case (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    programme_id uuid NOT NULL REFERENCES programme(id),
    case_id      uuid NOT NULL REFERENCES onboarding_case(id),
    added_at     timestamptz NOT NULL,
    added_by     uuid NULL REFERENCES app_user(id),
    removed_at   timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL
);
-- Partial, not plain: a journey belongs to at most one programme AT A TIME, but
-- DELETE is revoked, so leaving one and joining another must stay possible.
CREATE UNIQUE INDEX programme_case_active_uq
    ON programme_case (case_id) WHERE removed_at IS NULL;
CREATE INDEX programme_case_programme_idx ON programme_case (programme_id, removed_at);

SELECT enable_tenant_rls('programme');
SELECT enable_tenant_rls('programme_participant');
SELECT enable_tenant_rls('programme_case');

GRANT SELECT, INSERT, UPDATE ON programme             TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON programme_participant TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON programme_case        TO onboarding_app;
