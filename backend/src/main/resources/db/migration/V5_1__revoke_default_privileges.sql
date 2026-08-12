-- V2's ALTER DEFAULT PRIVILEGES grants SELECT, INSERT, UPDATE (DELETE
-- narrowed away by V2_1) on every table created afterwards, including
-- partitions of a partitioned table that itself grants nothing directly.
-- audit_event's partitions (V5) inherited that default grant the moment
-- each CREATE TABLE ... PARTITION OF ran. V5's own GRANT/REVOKE and its
-- enable_tenant_rls('audit_event') apply only to the parent relation, so
-- onboarding_app could read and modify audit rows across every tenant by
-- naming a partition (e.g. audit_event_2026_08) instead of audit_event.
-- AuditAppendOnlyTest.applicationRoleCannotAccessPartitionsDirectly proved
-- this red before this migration existed.
--
-- Every table in this project already grants privileges explicitly in its
-- own migration (V2 for tenant, V4 for the identity tables, V5 for
-- audit_event), so the schema-wide default grant is redundant as well as
-- dangerous. Remove it so no future table or partition inherits anything
-- by default.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL ON TABLES FROM onboarding_app;

-- Strip what the three existing audit_event partitions already inherited
-- from the default-privilege grant before the REVOKE above took effect.
-- (ALTER DEFAULT PRIVILEGES only changes what happens for objects created
-- AFTER it runs -- it is not retroactive.)
REVOKE ALL ON audit_event_2026_08, audit_event_2026_09, audit_event_default
    FROM onboarding_app;

-- Defence in depth: RLS on the partitions themselves. A query that goes
-- through the parent audit_event applies only the PARENT's policies --
-- partition-level RLS constrains direct partition access, which is exactly
-- the gap this migration closes. onboarding_app now has zero privileges on
-- these relations from the REVOKE above, so RLS is a second, independent
-- barrier rather than the only one: a future GRANT added in error to a
-- partition alone would still be caught by tenant isolation here.
--
-- Confirmed empirically that this does not change behaviour through the
-- parent: AuditRecorderTest, which reads and writes exclusively through the
-- audit_event parent via JPA, passes unchanged with this in place, because
-- PostgreSQL evaluates only the partitioned root's policies when the query
-- targets the root.
SELECT enable_tenant_rls('audit_event_2026_08');
SELECT enable_tenant_rls('audit_event_2026_09');
SELECT enable_tenant_rls('audit_event_default');
