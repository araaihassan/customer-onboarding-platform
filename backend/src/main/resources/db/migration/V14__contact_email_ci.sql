-- app_user is unique on (tenant_id, lower(email)) (V4), but customer_contact's
-- V8 UNIQUE (customer_id, email) is case-SENSITIVE -- so two contacts on one
-- customer differing only in case ("Person@acme.test" / "person@acme.test")
-- were both accepted, and the second one's portal activation always failed:
-- ActivationService.activateContact resolves app_user by
-- findByTenantIdAndEmailIgnoreCase, sees the first contact's account already
-- there, and answers "invalid token" -- a confusing, undiagnosable failure for
-- an input customer_contact itself should never have allowed.

-- Fail loudly rather than silently resolving a collision: business records are
-- never deleted (spec 9.4), so this migration must not choose one of a
-- colliding pair to keep. A DO block raising an exception is sufficient here --
-- there is no data in any environment this has actually run against yet, and an
-- operator hitting this message has an explicit, actionable failure instead of
-- a migration that silently dropped a row.
DO $$
DECLARE
    collision_count integer;
BEGIN
    SELECT count(*) INTO collision_count
    FROM (
        SELECT customer_id, lower(email)
        FROM customer_contact
        GROUP BY customer_id, lower(email)
        HAVING count(*) > 1
    ) collisions;

    IF collision_count > 0 THEN
        RAISE EXCEPTION 'customer_contact has % case-differing email collision(s) under (customer_id, lower(email)); resolve them manually before this migration can run -- business records are never deleted, so this migration will not choose one to keep', collision_count;
    END IF;
END $$;

ALTER TABLE customer_contact DROP CONSTRAINT customer_contact_customer_id_email_key;

-- A plain UNIQUE column-list constraint cannot express lower(email), so this
-- needs an explicit CREATE UNIQUE INDEX rather than a renamed constraint --
-- and therefore an explicit name, since Postgres only auto-derives one for a
-- table-level UNIQUE constraint. CustomerContactService.CONTACT_EMAIL_UNIQUE
-- must match this exact string, the same way it matched V8's auto-derived name.
CREATE UNIQUE INDEX customer_contact_customer_id_lower_email_idx
    ON customer_contact (customer_id, lower(email));
