-- Sub-project 2, half two: the runtime. Note what is NOT here -- no progress column a
-- client can write to, no denormalised milestone name, and no second event store: the
-- Activity Timeline reads audit_event, filtered by resource, because two stores would
-- need dual writes that inevitably drift.

CREATE TABLE onboarding_case (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    customer_id             uuid NOT NULL REFERENCES customer(id),
    template_id             uuid NOT NULL REFERENCES workflow_template(id),
    -- NOT NULL: a case with no pinned version has no definition to execute. Q2's
    -- freeze-by-default is this column plus V12's trigger, nothing else.
    version_id              uuid NOT NULL REFERENCES workflow_version(id),
    status                  varchar(16) NOT NULL,
    current_stage_id        uuid REFERENCES stage(id),
    -- Written only by CaseEngine, never from a request body. Stored rather than derived
    -- because sub-projects 8 and 9 read it per case in list views, and deriving it there
    -- is a join over every requirement in the tenant.
    progress_percent        int  NOT NULL DEFAULT 0,
    target_completion_date  date,
    held_at                 timestamptz,
    total_hold_days         int  NOT NULL DEFAULT 0,
    -- Copied from the customer at creation, so the case and milestone descriptors read
    -- their own columns instead of joining customer to resolve scope -- which is what
    -- keeps journey free of any customer import.
    owner_user_id           uuid REFERENCES app_user(id),
    owning_department_id    uuid REFERENCES department(id),
    owning_team_id          uuid REFERENCES team(id),
    started_at              timestamptz NOT NULL,
    completed_at            timestamptz,
    created_by              uuid REFERENCES app_user(id),
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL
);

CREATE TABLE case_participant (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    case_id      uuid NOT NULL REFERENCES onboarding_case(id),
    user_id      uuid NOT NULL REFERENCES app_user(id),
    -- authz.RelationshipType. ASSIGNED scope resolves through these rows, which is the
    -- first real use of ResourceAuthorizationDescriptor.assignedRelationships().
    relationship varchar(16) NOT NULL,
    status       varchar(16) NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    UNIQUE (case_id, user_id, relationship)
);

CREATE TABLE milestone (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    -- case_id is on milestone, requirement and approval alike so each descriptor is ONE
    -- subquery hop to onboarding_case rather than a chain, and every index can lead
    -- (tenant_id, case_id).
    case_id                 uuid NOT NULL REFERENCES onboarding_case(id),
    milestone_definition_id uuid NOT NULL REFERENCES milestone_definition(id),
    status                  varchar(16) NOT NULL,
    owner_user_id           uuid REFERENCES app_user(id),
    due_date                date,
    progress_percent        int  NOT NULL DEFAULT 0,
    completed_at            timestamptz,
    completed_by            uuid REFERENCES app_user(id),
    -- Only set when a completion was forced. Q5 requires the reason to be recorded, and
    -- a nullable column here plus a NOT NULL one on approval is the difference between
    -- "completed" and "forced".
    completion_reason       text,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    UNIQUE (case_id, milestone_definition_id)
);

CREATE TABLE requirement (
    id                       uuid PRIMARY KEY,
    tenant_id                uuid NOT NULL REFERENCES tenant(id),
    case_id                  uuid NOT NULL REFERENCES onboarding_case(id),
    milestone_id             uuid NOT NULL REFERENCES milestone(id),
    requirement_definition_id uuid NOT NULL REFERENCES requirement_definition(id),
    status                   varchar(16) NOT NULL,
    satisfied_at             timestamptz,
    satisfied_by             uuid REFERENCES app_user(id),
    -- Deliberately NOT a foreign key: the target is a task, document or agreement in a
    -- module that does not exist yet. It also avoids the shape behind sub-project 1's
    -- cross-tenant existence oracle -- PostgreSQL checks referential integrity with row
    -- security BYPASSED, so an FK answers 200 for another tenant's id and 500 for an
    -- invented one. A soft reference resolved through AuthorizedQuery cannot.
    satisfied_ref            uuid,
    satisfied_ref_type       varchar(32),
    waiver_reason            text,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,
    UNIQUE (milestone_id, requirement_definition_id)
);

CREATE TABLE case_attribute_value (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    case_id                 uuid NOT NULL REFERENCES onboarding_case(id),
    attribute_definition_id uuid NOT NULL REFERENCES attribute_definition(id),
    -- Typed columns rather than one text column plus a parse at evaluation time.
    -- Condition evaluation must never fail on a malformed stored value: a branch that
    -- throws mid-transition leaves a case wedged, and one that swallows the error is a
    -- silent false.
    value_text              varchar(255),
    value_number            numeric(18,4),
    value_boolean           boolean,
    value_date              date,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    UNIQUE (case_id, attribute_definition_id)
);

CREATE TABLE approval (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    case_id       uuid NOT NULL REFERENCES onboarding_case(id),
    kind          varchar(24) NOT NULL,
    -- Exactly one of these is set, by kind: STAGE_EXIT approves leaving a stage,
    -- FORCE_COMPLETE approves forcing one milestone.
    stage_id      uuid REFERENCES stage(id),
    milestone_id  uuid REFERENCES milestone(id),
    requested_by  uuid NOT NULL REFERENCES app_user(id),
    requested_at  timestamptz NOT NULL,
    -- NOT NULL is how Q5's "mandatory reason recorded in audit trail" becomes
    -- unavoidable: the flow cannot be built without one.
    reason        text NOT NULL,
    status        varchar(16) NOT NULL,
    decided_by    uuid REFERENCES app_user(id),
    decided_at    timestamptz,
    decision_note text,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT approval_target_matches_kind CHECK (
        (kind = 'STAGE_EXIT'     AND stage_id IS NOT NULL AND milestone_id IS NULL) OR
        (kind = 'FORCE_COMPLETE' AND milestone_id IS NOT NULL AND stage_id IS NULL))
);

CREATE INDEX onboarding_case_tenant_customer_idx ON onboarding_case (tenant_id, customer_id);
CREATE INDEX onboarding_case_tenant_status_idx   ON onboarding_case (tenant_id, status);
CREATE INDEX onboarding_case_tenant_team_idx     ON onboarding_case (tenant_id, owning_team_id);
CREATE INDEX onboarding_case_tenant_owner_idx    ON onboarding_case (tenant_id, owner_user_id);
CREATE INDEX case_participant_tenant_case_idx    ON case_participant (tenant_id, case_id);
CREATE INDEX case_participant_tenant_user_idx    ON case_participant (tenant_id, user_id, status);
CREATE INDEX milestone_tenant_case_idx           ON milestone (tenant_id, case_id);
CREATE INDEX milestone_tenant_owner_idx          ON milestone (tenant_id, owner_user_id);
CREATE INDEX requirement_tenant_case_idx         ON requirement (tenant_id, case_id);
CREATE INDEX requirement_tenant_milestone_idx    ON requirement (tenant_id, milestone_id);
CREATE INDEX case_attribute_value_tenant_case_idx ON case_attribute_value (tenant_id, case_id);
CREATE INDEX approval_tenant_case_status_idx     ON approval (tenant_id, case_id, status);

SELECT enable_tenant_rls('onboarding_case');
SELECT enable_tenant_rls('case_participant');
SELECT enable_tenant_rls('milestone');
SELECT enable_tenant_rls('requirement');
SELECT enable_tenant_rls('case_attribute_value');
SELECT enable_tenant_rls('approval');

-- SELECT, INSERT, UPDATE only, and no DELETE anywhere: every one of these is a business
-- record. V5_1 revoked the schema-wide default, so these tables start with nothing.
GRANT SELECT, INSERT, UPDATE ON
    onboarding_case, case_participant, milestone, requirement,
    case_attribute_value, approval
    TO onboarding_app;
