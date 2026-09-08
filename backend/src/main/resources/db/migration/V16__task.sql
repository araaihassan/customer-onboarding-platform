-- Sub-project 3, Task 11: the substrate every later task in this phase builds on --
-- ad-hoc and requirement-instantiated tasks, their checklists, and comments on
-- both tasks and journeys. No new caller of CaseEngine.reconcile: task completion
-- routes through the existing gated RequirementService.satisfy (spec section 3).

CREATE TABLE task (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    case_id             uuid NOT NULL REFERENCES onboarding_case(id),
    milestone_id        uuid NOT NULL REFERENCES milestone(id),
    requirement_id      uuid     NULL REFERENCES requirement(id),
    title               text NOT NULL,
    description         text,
    priority            text NOT NULL,
    status              text NOT NULL,
    assignee_id         uuid     NULL REFERENCES app_user(id),
    due_date            date,
    completed_at        timestamptz,
    completed_by        uuid     NULL REFERENCES app_user(id),
    cancelled_at        timestamptz,
    cancellation_reason text,
    attachment_ref      uuid     NULL,
    attachment_ref_type text     NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT task_priority_ck CHECK (priority IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT task_status_ck   CHECK (status IN
        ('PENDING','IN_PROGRESS','WAITING','COMPLETED','CANCELLED')),
    -- Cancelling without a reason is the silent-waiver path this design refuses.
    CONSTRAINT task_cancel_reason_ck CHECK (
        status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
);
-- A requirement is satisfied by at most one task.
CREATE UNIQUE INDEX task_requirement_uq ON task (requirement_id)
    WHERE requirement_id IS NOT NULL;
-- "My work" is a cross-case query; this is why case_id is denormalised.
CREATE INDEX task_tenant_assignee_idx ON task (tenant_id, assignee_id, status);
CREATE INDEX task_tenant_case_idx     ON task (tenant_id, case_id);

CREATE TABLE task_checklist_item (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    task_id    uuid NOT NULL REFERENCES task(id),
    label      text NOT NULL,
    done       boolean NOT NULL DEFAULT false,
    ordinal    int NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX task_checklist_task_idx ON task_checklist_item (tenant_id, task_id, ordinal);

CREATE TABLE comment (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    case_id       uuid NOT NULL REFERENCES onboarding_case(id),
    resource_type text NOT NULL,
    resource_id   uuid NOT NULL,
    author_id     uuid NOT NULL REFERENCES app_user(id),
    body          text NOT NULL,
    edited_at     timestamptz,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    -- Half the gate. The Java enum is the other half; adding a third value
    -- requires a migration AND a compile error, which is the point.
    CONSTRAINT comment_resource_type_ck CHECK (
        resource_type IN ('task','onboarding_case')),
    CONSTRAINT comment_body_ck CHECK (length(btrim(body)) > 0)
);
CREATE INDEX comment_tenant_resource_idx
    ON comment (tenant_id, resource_type, resource_id, created_at);

SELECT enable_tenant_rls('task');
SELECT enable_tenant_rls('task_checklist_item');
SELECT enable_tenant_rls('comment');

GRANT SELECT, INSERT, UPDATE ON task                TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON task_checklist_item TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON comment             TO onboarding_app;
