CREATE TABLE refresh_token (
    id          uuid PRIMARY KEY,
    -- Denormalized from the owning user so this table carries RLS like every
    -- other tenant-owned table rather than relying on a join (spec 7.3). It is
    -- also what makes a token from one tenant invisible on another tenant's
    -- path, since the refresh endpoint runs with the tenant already resolved.
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    user_id     uuid NOT NULL REFERENCES app_user(id),
    -- SHA-256 hex: 64 characters. The raw token is returned to the client once
    -- and never stored, so a database disclosure yields no usable tokens.
    token_hash  varchar(64) NOT NULL UNIQUE,
    -- All tokens rotated from one login share a family. Reuse of any retired
    -- member revokes the whole family, which is what turns rotation from breach
    -- resistance into breach detection (spec 7.4).
    family_id   uuid NOT NULL,
    issued_at   timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    revoked_at  timestamptz,
    ip          varchar(64),
    user_agent  text,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE INDEX refresh_token_family_idx ON refresh_token (tenant_id, family_id);

SELECT enable_tenant_rls('refresh_token');

-- No DELETE: tokens are retired via used_at / revoked_at, never removed. A
-- deleted row is a row that cannot be recognised as replayed, which would turn
-- a detected theft into a silently accepted one.
GRANT SELECT, INSERT, UPDATE ON refresh_token TO onboarding_app;
