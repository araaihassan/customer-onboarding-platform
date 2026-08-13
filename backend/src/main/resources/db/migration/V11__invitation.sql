-- One table for both token flows, distinguished by `purpose`. They share a
-- lifecycle (issue once, single use, expire, revocable) and differ only in
-- lifetime and in which column they point at, so two tables would duplicate the
-- validation logic. The consequence is that `purpose` must be checked on every
-- redemption, not just token validity -- an activation token is valid for seven
-- days against a password reset's one hour.
CREATE TABLE invitation (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    purpose              varchar(24) NOT NULL,   -- ACTIVATION | PASSWORD_RESET
    -- Exactly one applies: activation points at the contact being invited,
    -- password reset at the existing user. Both nullable for that reason.
    customer_contact_id  uuid REFERENCES customer_contact(id),
    user_id              uuid REFERENCES app_user(id),
    -- SHA-256 hex. The raw token goes out in an email and is never stored.
    token_hash           varchar(64) NOT NULL UNIQUE,
    expires_at           timestamptz NOT NULL,
    accepted_at          timestamptz,
    revoked_at           timestamptz,
    created_by           uuid REFERENCES app_user(id),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL
);

CREATE INDEX invitation_contact_idx ON invitation (tenant_id, customer_contact_id);
CREATE INDEX invitation_user_idx    ON invitation (tenant_id, user_id);

SELECT enable_tenant_rls('invitation');

-- No DELETE: a redeemed token is retired by stamping accepted_at, never removed,
-- so a replay is recognisable rather than indistinguishable from a fresh token.
GRANT SELECT, INSERT, UPDATE ON invitation TO onboarding_app;
