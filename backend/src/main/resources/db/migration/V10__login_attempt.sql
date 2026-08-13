CREATE TABLE login_attempt (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    email          varchar(320) NOT NULL,
    failure_count  int NOT NULL DEFAULT 0,
    first_failure  timestamptz,
    locked_until   timestamptz,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);

-- Unique on lower(email), NOT on the raw string. app_user is already unique on
-- lower(email), so counting failures case-sensitively would give an attacker five
-- fresh attempts per capitalisation of one address -- a complete lockout bypass.
-- LoginThrottleService normalises before writing; this index is what makes that
-- impossible to forget.
CREATE UNIQUE INDEX login_attempt_tenant_email_key
    ON login_attempt (tenant_id, lower(email));

SELECT enable_tenant_rls('login_attempt');

GRANT SELECT, INSERT, UPDATE ON login_attempt TO onboarding_app;

-- Explicit DELETE (deny-by-default since V2_1): a successful login clears the
-- counter by removing the row. This is session bookkeeping, not a business
-- record, so it is one of the few tables where deletion is correct.
GRANT DELETE ON login_attempt TO onboarding_app;
