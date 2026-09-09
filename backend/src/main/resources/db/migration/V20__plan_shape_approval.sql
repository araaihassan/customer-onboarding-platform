-- Sub-project 3A, Task 19: gate 1 of QA Q22 -- the customer approves the SHAPE of a
-- plan (stages, milestones, requirements, estimated durations), once per version of
-- their own template. Every journey pinned to that version inherits the approval.
--
-- A separate table rather than columns on workflow_version because
-- workflow_version_frozen refuses every UPDATE to a non-DRAFT row (V12). There is no
-- snapshot here and none is needed: publish already froze the graph and its
-- portal_visible flags, so "what shape did they approve?" is answered by reading the
-- version. Gate 2 needs a snapshot only because milestone.due_date and owner_user_id
-- are mutable runtime columns with nothing freezing them.
CREATE TABLE plan_shape_approval (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    version_id           uuid NOT NULL REFERENCES workflow_version(id),
    template_id          uuid NOT NULL REFERENCES workflow_template(id),
    customer_id          uuid NOT NULL REFERENCES customer(id),
    status               text NOT NULL,
    submitted_at         timestamptz NOT NULL,
    submitted_by         uuid NOT NULL REFERENCES app_user(id),
    decided_at           timestamptz,
    -- The user who pressed it. Internal until sub-project 7, the sponsor thereafter --
    -- and nothing about this record's shape changes when that happens.
    decided_by           uuid NULL REFERENCES app_user(id),
    -- The customer's own person, while the approver is still internal:
    -- "the sponsor approved by email, logged by the account manager".
    decided_on_behalf_of uuid NULL REFERENCES customer_contact(id),
    decision_note        text,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT plan_shape_approval_status_ck CHECK (status IN ('SUBMITTED','APPROVED','REJECTED')),
    CONSTRAINT plan_shape_approval_decided_ck CHECK (
        (status = 'SUBMITTED' AND decided_at IS NULL AND decided_by IS NULL)
     OR (status <> 'SUBMITTED' AND decided_at IS NOT NULL AND decided_by IS NOT NULL))
);
CREATE INDEX plan_shape_approval_version_idx ON plan_shape_approval (version_id, submitted_at DESC);

SELECT enable_tenant_rls('plan_shape_approval');
GRANT SELECT, INSERT, UPDATE ON plan_shape_approval TO onboarding_app;
