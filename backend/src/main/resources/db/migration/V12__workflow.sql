-- Sub-project 2, half one: the definition side. Nothing here references a case and
-- nothing here counts them -- "31 cases on v4" is computed in journey at read time. A
-- workflow table gaining a case column would make a definition module depend on
-- runtime state, which ModuleBoundaryTest.noWorkflowDependencyOnJourney forbids in
-- code and this file forbids in schema.

CREATE TABLE workflow_template (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenant(id),
    name               varchar(160) NOT NULL,
    description        text,
    status             varchar(16) NOT NULL,
    current_version_id uuid,
    created_by         uuid REFERENCES app_user(id),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);

-- lower(name), not name. Sub-project 1 shipped customer_contact unique on
-- (customer_id, email) case-SENSITIVELY while app_user was unique on
-- (tenant_id, lower(email)); the disagreement accepted two contacts differing only in
-- case and then failed the second one's activation as an "invalid token".
CREATE UNIQUE INDEX workflow_template_tenant_name_key
    ON workflow_template (tenant_id, lower(name));

CREATE TABLE workflow_version (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    template_id  uuid NOT NULL REFERENCES workflow_template(id),
    version_no   int  NOT NULL,
    status       varchar(16) NOT NULL,
    -- JPA @Version. Two administrators editing one draft is the normal case in a small
    -- tenant, and last-writer-wins on a whole-graph PUT silently discards the other's
    -- stages.
    lock_version bigint NOT NULL DEFAULT 0,
    published_at timestamptz,
    published_by uuid REFERENCES app_user(id),
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    UNIQUE (template_id, version_no)
);

-- One draft per template: two admins editing the same workflow collide immediately
-- rather than silently forking two drafts that both later claim to be v5.
CREATE UNIQUE INDEX workflow_version_one_draft_per_template
    ON workflow_version (template_id) WHERE status = 'DRAFT';

ALTER TABLE workflow_template
    ADD CONSTRAINT workflow_template_current_version_fk
    FOREIGN KEY (current_version_id) REFERENCES workflow_version(id);

-- Every child table carries version_id, even where a parent would reach it. Two
-- reasons: the immutability trigger below is then one uniform function rather than one
-- per depth, and loading a whole definition is five indexed reads by version_id
-- instead of a nested join per level.
CREATE TABLE stage (
    id                        uuid PRIMARY KEY,
    tenant_id                 uuid NOT NULL REFERENCES tenant(id),
    version_id                uuid NOT NULL REFERENCES workflow_version(id),
    ordinal                   int  NOT NULL,
    name                      varchar(160) NOT NULL,
    responsible_department_id uuid REFERENCES department(id),
    requires_approval         boolean NOT NULL DEFAULT false,
    auto_advance              boolean NOT NULL DEFAULT true,
    portal_visible            boolean NOT NULL DEFAULT true,
    -- Expected effort drives the schedule (milestone_definition below); sla_days is the
    -- promise the tenant makes about this stage and drives breach in sub-project 6. A
    -- stage can be planned at three days and promised in five.
    sla_days                  int,
    -- Subtractive only: it narrows who may write inside this stage after the permission
    -- gate has already said yes, and has no branch that grants (§6.3).
    write_scope               varchar(16) NOT NULL DEFAULT 'ANY',
    -- Authored here, acted on in sub-project 6. The builder renders it disabled.
    notification_template_key varchar(64),
    entry_source              varchar(16),
    entry_key                 varchar(64),
    entry_operator            varchar(8),
    entry_value               varchar(255),
    entry_values              text[],
    fallback_next_stage_id    uuid REFERENCES stage(id),
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    UNIQUE (version_id, ordinal)
);

CREATE TABLE milestone_definition (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    version_id              uuid NOT NULL REFERENCES workflow_version(id),
    stage_id                uuid NOT NULL REFERENCES stage(id),
    ordinal                 int  NOT NULL,
    name                    varchar(160) NOT NULL,
    description             text,
    -- Q6's weight, and the schedule's unit. NOT NULL: a milestone with no duration
    -- contributes nothing to a weighted progress calculation, which reads as a
    -- milestone that does not count.
    estimated_duration_days int  NOT NULL,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    UNIQUE (stage_id, ordinal)
);

CREATE TABLE milestone_dependency (
    id                                 uuid PRIMARY KEY,
    tenant_id                          uuid NOT NULL REFERENCES tenant(id),
    version_id                         uuid NOT NULL REFERENCES workflow_version(id),
    milestone_definition_id            uuid NOT NULL REFERENCES milestone_definition(id),
    depends_on_milestone_definition_id uuid NOT NULL REFERENCES milestone_definition(id),
    created_at                         timestamptz NOT NULL,
    updated_at                         timestamptz NOT NULL,
    UNIQUE (milestone_definition_id, depends_on_milestone_definition_id)
);

CREATE TABLE requirement_definition (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    version_id              uuid NOT NULL REFERENCES workflow_version(id),
    milestone_definition_id uuid NOT NULL REFERENCES milestone_definition(id),
    ordinal                 int  NOT NULL,
    kind                    varchar(16) NOT NULL,
    label                   varchar(200) NOT NULL,
    weight                  int  NOT NULL DEFAULT 1,
    mandatory               boolean NOT NULL DEFAULT true,
    -- Typed nullable columns per kind, not a params jsonb. A JSON bag invites
    -- sub-projects 3-5 to write whatever they like into a column nobody validates; a
    -- typed column makes each of them add a forward-only migration deliberately.
    document_category       varchar(64),
    approver_relationship   varchar(16),
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    UNIQUE (milestone_definition_id, ordinal)
);

CREATE TABLE attribute_definition (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    version_id     uuid NOT NULL REFERENCES workflow_version(id),
    ordinal        int  NOT NULL,
    key            varchar(64)  NOT NULL,
    label          varchar(200) NOT NULL,
    data_type      varchar(16)  NOT NULL,
    required       boolean NOT NULL DEFAULT false,
    allowed_values text[],
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    UNIQUE (version_id, key)
);

CREATE TABLE branch_rule (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    version_id      uuid NOT NULL REFERENCES workflow_version(id),
    stage_id        uuid NOT NULL REFERENCES stage(id),
    ordinal         int  NOT NULL,
    source          varchar(16) NOT NULL,
    key             varchar(64) NOT NULL,
    operator        varchar(8)  NOT NULL,
    value           varchar(255),
    values          text[],
    target_stage_id uuid NOT NULL REFERENCES stage(id),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (stage_id, ordinal)
);

-- Every index leads with tenant_id: RLS adds a tenant_id predicate to every query, so
-- an index without it cannot serve one.
CREATE INDEX workflow_version_tenant_template_idx  ON workflow_version (tenant_id, template_id);
CREATE INDEX stage_tenant_version_idx              ON stage (tenant_id, version_id);
CREATE INDEX milestone_definition_tenant_ver_idx   ON milestone_definition (tenant_id, version_id);
CREATE INDEX milestone_dependency_tenant_ver_idx   ON milestone_dependency (tenant_id, version_id);
CREATE INDEX requirement_definition_tenant_ver_idx ON requirement_definition (tenant_id, version_id);
CREATE INDEX attribute_definition_tenant_ver_idx   ON attribute_definition (tenant_id, version_id);
CREATE INDEX branch_rule_tenant_version_idx        ON branch_rule (tenant_id, version_id);

-- Immutability. audit_event is append-only by GRANT because an audit trail the
-- application can rewrite is not evidence; a published workflow is frozen by trigger
-- for the same reason -- every running case is pinned to it, and "frozen" enforced only
-- in the service that happens to write it is a promise, not a property.
CREATE OR REPLACE FUNCTION refuse_published_version_change() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'workflow version % is published and cannot be modified', OLD.id;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

-- Fails CLOSED: only an explicitly DRAFT parent permits a write. Written as IS DISTINCT
-- FROM rather than <> for exactly that reason -- a row hidden by RLS or a missing parent
-- yields NULL, `NULL <> 'DRAFT'` is NULL, and an IF treats NULL as false, so the write
-- would have been allowed.
CREATE OR REPLACE FUNCTION refuse_published_child_write() RETURNS trigger AS $$
DECLARE
    v_id     uuid := COALESCE(OLD.version_id, NEW.version_id);
    v_status text;
BEGIN
    SELECT status INTO v_status FROM workflow_version WHERE id = v_id;
    IF v_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'workflow version % is published and cannot be modified', v_id;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workflow_version_frozen
    BEFORE UPDATE OR DELETE ON workflow_version
    FOR EACH ROW EXECUTE FUNCTION refuse_published_version_change();

CREATE TRIGGER stage_frozen BEFORE UPDATE OR DELETE ON stage
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();
CREATE TRIGGER milestone_definition_frozen BEFORE UPDATE OR DELETE ON milestone_definition
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();
CREATE TRIGGER milestone_dependency_frozen BEFORE UPDATE OR DELETE ON milestone_dependency
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();
CREATE TRIGGER requirement_definition_frozen BEFORE UPDATE OR DELETE ON requirement_definition
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();
CREATE TRIGGER attribute_definition_frozen BEFORE UPDATE OR DELETE ON attribute_definition
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();
CREATE TRIGGER branch_rule_frozen BEFORE UPDATE OR DELETE ON branch_rule
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();

SELECT enable_tenant_rls('workflow_template');
SELECT enable_tenant_rls('workflow_version');
SELECT enable_tenant_rls('stage');
SELECT enable_tenant_rls('milestone_definition');
SELECT enable_tenant_rls('milestone_dependency');
SELECT enable_tenant_rls('requirement_definition');
SELECT enable_tenant_rls('attribute_definition');
SELECT enable_tenant_rls('branch_rule');

GRANT SELECT, INSERT, UPDATE ON
    workflow_template, workflow_version, stage, milestone_definition,
    milestone_dependency, requirement_definition, attribute_definition, branch_rule
    TO onboarding_app;

-- DELETE on the seven definition tables, and deliberately NOT on workflow_template.
-- Editing a draft must be able to remove a stage, and discarding a draft must delete
-- it; an unpublished definition row is configuration bookkeeping -- the same category
-- as role, role_grant, user_role, team_member and login_attempt in sub-project 1. What
-- makes the grant safe is the trigger above: a DELETE whose version is not explicitly
-- DRAFT is refused, so deletion is reachable for drafts only. A template is a business
-- record and is deactivated instead.
GRANT DELETE ON
    workflow_version, stage, milestone_definition, milestone_dependency,
    requirement_definition, attribute_definition, branch_rule
    TO onboarding_app;
