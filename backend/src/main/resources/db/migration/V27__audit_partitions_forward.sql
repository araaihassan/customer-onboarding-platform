-- V5 created audit_event partitions for 2026-08 and 2026-09 only, so since
-- 2026-10-01 every audit row has been landing in audit_event_default. That
-- loses nothing, but Postgres refuses to create a month's partition while
-- DEFAULT holds rows in its range, so the backlog has to be moved out first --
-- and the move only grows the longer it waits. This migration:
--
--   1. defines create_audit_event_partition(month), the one place a partition
--      is created and hardened (sub-project 6's roll-forward job calls it too);
--   2. lifts the rows DEFAULT holds for the months about to get partitions;
--   3. creates partitions 2026-10 through 2027-12;
--   4. re-inserts the lifted rows through the parent, which routes each to
--      its month. Ids, timestamps and payloads are unchanged.
--
-- Runs as the Flyway owner (a superuser), so RLS does not filter the move.
-- audit_event stays append-only for onboarding_app: nothing here grants it
-- anything, and the DELETE below is the owner relocating rows, not erasing them.

CREATE OR REPLACE FUNCTION create_audit_event_partition(month date)
RETURNS text AS $$
DECLARE
    month_start date := date_trunc('month', month)::date;
    partition_name text := format('audit_event_%s', to_char(month_start, 'YYYY_MM'));
BEGIN
    IF to_regclass(partition_name) IS NOT NULL THEN
        RETURN partition_name;
    END IF;

    EXECUTE format(
        'CREATE TABLE %I PARTITION OF audit_event FOR VALUES FROM (%L) TO (%L)',
        partition_name, month_start, (month_start + interval '1 month')::date);

    -- The same hardening V5_1 gave the first three partitions. V5_1 already
    -- removed the schema-wide default grant, so a new partition starts with no
    -- privileges for onboarding_app; the REVOKE states that explicitly rather
    -- than relying on it. RLS on the partition is the second, independent
    -- barrier against anyone naming it directly instead of going through
    -- audit_event (AuditAppendOnlyTest sweeps every partition for both).
    EXECUTE format('REVOKE ALL ON %I FROM onboarding_app', partition_name);
    PERFORM enable_tenant_rls(partition_name);

    RETURN partition_name;
END;
$$ LANGUAGE plpgsql;

CREATE TEMP TABLE audit_event_relocating ON COMMIT DROP AS
    SELECT * FROM audit_event_default
     WHERE occurred_at >= '2026-10-01' AND occurred_at < '2028-01-01';

DELETE FROM audit_event_default
 WHERE occurred_at >= '2026-10-01' AND occurred_at < '2028-01-01';

SELECT create_audit_event_partition(month::date)
  FROM generate_series('2026-10-01'::date, '2027-12-01'::date, interval '1 month') AS month;

INSERT INTO audit_event SELECT * FROM audit_event_relocating;
