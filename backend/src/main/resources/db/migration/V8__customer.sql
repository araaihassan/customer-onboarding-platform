CREATE TABLE customer (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid NOT NULL REFERENCES tenant(id),
    legal_name            varchar(255) NOT NULL,
    display_name          varchar(255) NOT NULL,
    status                varchar(16)  NOT NULL,
    industry              varchar(128),
    country               varchar(2),
    external_ref          varchar(128),
    -- The three ownership columns are what the DEPARTMENT, TEAM and ASSIGNED
    -- scopes resolve against (Task 13's predicate builder). All nullable: a
    -- prospect can exist before anyone owns it.
    owner_user_id         uuid REFERENCES app_user(id),
    owning_department_id  uuid REFERENCES department(id),
    owning_team_id        uuid REFERENCES team(id),
    created_by            uuid REFERENCES app_user(id),
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL
);

-- Every index leads with tenant_id: RLS adds a tenant_id predicate to every
-- query, so an index without it cannot serve one.
CREATE INDEX customer_tenant_status_idx ON customer (tenant_id, status);
CREATE INDEX customer_tenant_owner_idx  ON customer (tenant_id, owner_user_id);
CREATE INDEX customer_tenant_team_idx   ON customer (tenant_id, owning_team_id);

CREATE TABLE customer_contact (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant(id),
    customer_id      uuid NOT NULL REFERENCES customer(id),
    -- Nullable until the portal invitation is accepted: a contact is a business
    -- record that exists whether or not it ever gets a login (spec 9.1, QA Q12).
    -- Portal-access state is NOT duplicated here; it is read from app_user.status.
    user_id          uuid REFERENCES app_user(id),
    full_name        varchar(255) NOT NULL,
    email            varchar(320) NOT NULL,
    title            varchar(128),
    phone            varchar(64),
    primary_contact  boolean NOT NULL DEFAULT false,
    status           varchar(16) NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    UNIQUE (customer_id, email)
);

CREATE INDEX customer_contact_customer_idx ON customer_contact (tenant_id, customer_id);

SELECT enable_tenant_rls('customer');
SELECT enable_tenant_rls('customer_contact');

-- SELECT, INSERT, UPDATE only. No DELETE is granted here and none is inherited:
-- V5_1 revoked the schema-wide ALTER DEFAULT PRIVILEGES grant, so a new table
-- starts with nothing. That is what makes "business records are deactivated,
-- never deleted" (spec 9.4) a database guarantee rather than a code convention,
-- and CustomerPersistenceTest.applicationRoleCannotDeleteBusinessRecords is what
-- proves it. Sub-projects 2-9 should follow this pattern for their own business
-- tables.
GRANT SELECT, INSERT, UPDATE ON customer, customer_contact TO onboarding_app;
