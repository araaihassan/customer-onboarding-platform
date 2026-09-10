-- Sub-project 3A, Task 22: gate 2 of QA Q22 -- the customer approves the SCHEDULE
-- (calendar dates and named owners), per journey, as a dated snapshot revision.
--
-- Typed rows rather than a jsonb blob for three reasons: diffing revision N against
-- N-1 is a join instead of JSON extraction in application code; Q28's "milestones
-- closed in the interval" is a query; and V12's own comment already rejects a JSON
-- bag in favour of typed columns.
CREATE TABLE plan_revision (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    case_id              uuid NOT NULL REFERENCES onboarding_case(id),
    revision_number      integer NOT NULL,
    status               text NOT NULL,
    issued_at            timestamptz NOT NULL,
    issued_by            uuid NOT NULL REFERENCES app_user(id),
    issue_note           text,
    decided_at           timestamptz,
    decided_by           uuid NULL REFERENCES app_user(id),
    decided_on_behalf_of uuid NULL REFERENCES customer_contact(id),
    decision_note        text,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT plan_revision_status_ck CHECK (
        status IN ('ISSUED','APPROVED','REJECTED','SUPERSEDED')),
    CONSTRAINT plan_revision_number_ck CHECK (revision_number > 0)
);
CREATE UNIQUE INDEX plan_revision_case_number_uq ON plan_revision (case_id, revision_number);
-- At most one outstanding revision per case: issuing a new one supersedes the old.
CREATE UNIQUE INDEX plan_revision_one_outstanding_uq
    ON plan_revision (case_id) WHERE status = 'ISSUED';

CREATE TABLE plan_revision_item (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    plan_revision_id        uuid NOT NULL REFERENCES plan_revision(id),
    -- Denormalised so PlanRevisionItemDescriptor is one subquery hop to
    -- onboarding_case instead of a chain -- the reason milestone and comment both
    -- carry case_id too.
    case_id                 uuid NOT NULL REFERENCES onboarding_case(id),
    milestone_id            uuid NOT NULL REFERENCES milestone(id),
    milestone_definition_id uuid NOT NULL REFERENCES milestone_definition(id),
    stage_name              text NOT NULL,
    milestone_name          text NOT NULL,
    due_date                date,
    owner_user_id           uuid NULL REFERENCES app_user(id),
    estimated_duration_days integer NOT NULL,
    portal_visible          boolean NOT NULL,
    sort_order              integer NOT NULL,
    created_at              timestamptz NOT NULL
);
CREATE INDEX plan_revision_item_revision_idx ON plan_revision_item (plan_revision_id, sort_order);

SELECT enable_tenant_rls('plan_revision');
SELECT enable_tenant_rls('plan_revision_item');

GRANT SELECT, INSERT, UPDATE ON plan_revision TO onboarding_app;
-- No UPDATE and no DELETE. The second append-only table in this codebase, after
-- audit_event, and for the same reason: a snapshot the application can rewrite is
-- not evidence of what was sent.
GRANT SELECT, INSERT ON plan_revision_item TO onboarding_app;
