-- DELETE is deny-by-default. V2's ALTER DEFAULT PRIVILEGES granted it on all
-- future tables, which defeats per-table grants and contradicts the project's
-- no-hard-deletes rule. Tables that genuinely need DELETE grant it explicitly
-- in their own migration, with a comment saying why.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE DELETE ON TABLES FROM onboarding_app;

REVOKE DELETE ON tenant FROM onboarding_app;
