CREATE TABLE department (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    name         varchar(255) NOT NULL,
    description  text,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE TABLE team (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    department_id  uuid REFERENCES department(id),
    name           varchar(255) NOT NULL,
    description    text,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE TABLE app_user (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    email          varchar(320) NOT NULL,
    password_hash  varchar(255),
    user_type      varchar(16)  NOT NULL,
    status         varchar(16)  NOT NULL,
    full_name      varchar(255) NOT NULL,
    department_id  uuid REFERENCES department(id),
    mfa_enabled    boolean      NOT NULL DEFAULT false,
    mfa_secret     varchar(255),
    last_login_at  timestamptz,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL
);

-- Email is unique WITHIN a tenant, not globally (spec 5.2).
CREATE UNIQUE INDEX app_user_tenant_email_key
    ON app_user (tenant_id, lower(email));

CREATE TABLE team_member (
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    user_id    uuid NOT NULL REFERENCES app_user(id),
    team_id    uuid NOT NULL REFERENCES team(id),
    PRIMARY KEY (user_id, team_id)
);

-- Deliberately NOT tenant-scoped: vendor-side administration (spec 5.2).
CREATE TABLE platform_admin (
    id             uuid PRIMARY KEY,
    email          varchar(320) NOT NULL UNIQUE,
    password_hash  varchar(255) NOT NULL,
    full_name      varchar(255) NOT NULL,
    enabled        boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);

SELECT enable_tenant_rls('department');
SELECT enable_tenant_rls('team');
SELECT enable_tenant_rls('app_user');
SELECT enable_tenant_rls('team_member');

GRANT SELECT, INSERT, UPDATE ON department, team, app_user, team_member TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON platform_admin TO onboarding_app;

-- team_member is a pure join table: changing someone's teams means removing
-- rows. Users, departments and teams are deactivated, never deleted.
GRANT DELETE ON team_member TO onboarding_app;
