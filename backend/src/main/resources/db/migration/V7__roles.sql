CREATE TABLE role (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant(id),
    name             varchar(128) NOT NULL,
    description      text NOT NULL DEFAULT '',
    system_template  boolean NOT NULL DEFAULT false,
    enabled          boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE TABLE role_grant (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    role_id         uuid NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    -- FK to the catalog mirror: a grant cannot name a permission the catalog does
    -- not define. PermissionSyncRunner populates that table at startup, so the
    -- rows exist before any grant can be written.
    permission_key  varchar(64) NOT NULL REFERENCES permission(key),
    scope           varchar(16) NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    -- A role grants a given permission at exactly one scope (spec 6.6).
    UNIQUE (role_id, permission_key)
);

-- No id and no timestamps: this is a pure join table, which is why UserRole is a
-- standalone @IdClass entity rather than a TenantScopedEntity subclass (that
-- superclass would require all three columns).
CREATE TABLE user_role (
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    user_id    uuid NOT NULL REFERENCES app_user(id),
    role_id    uuid NOT NULL REFERENCES role(id),
    PRIMARY KEY (user_id, role_id)
);

SELECT enable_tenant_rls('role');
SELECT enable_tenant_rls('role_grant');
SELECT enable_tenant_rls('user_role');

GRANT SELECT, INSERT, UPDATE ON role, role_grant, user_role TO onboarding_app;

-- Explicit DELETE (deny-by-default since V2_1). These are authorization
-- metadata, not business records: a role is deletable once no users hold it,
-- updateGrants replaces a role's grants wholesale, and unassigning a role
-- removes its user_role row.
GRANT DELETE ON role, role_grant, user_role TO onboarding_app;
