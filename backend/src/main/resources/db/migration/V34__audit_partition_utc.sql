-- V33's ensure_audit_event_partitions computed its months with date_trunc('month', now()) and,
-- through V27's create_audit_event_partition, built timestamptz partition bounds from date
-- literals -- both interpreted in the SESSION TimeZone, which is whatever the JDBC driver sent
-- (the JVM default). A session zone that moved east between two adjacent months made the new
-- partition overlap its neighbour (the whole call fails, every day); west left a gap that routed
-- rows to DEFAULT. A function-level SET TimeZone applies to the nested create_audit_event_partition
-- call as well, so every partition this function makes is on exact UTC month boundaries.
-- Otherwise identical to V33 (SECURITY DEFINER, pinned search_path, same grants).
CREATE OR REPLACE FUNCTION ensure_audit_event_partitions(months_ahead int)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
SET TimeZone = 'UTC'
AS $$
DECLARE
    created int := 0;
    m date;
BEGIN
    IF months_ahead IS NULL OR months_ahead < 0 OR months_ahead > 36 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 36, got %', months_ahead;
    END IF;
    FOR m IN SELECT generate_series(date_trunc('month', now())::date,
                                    (date_trunc('month', now()) + make_interval(months => months_ahead))::date,
                                    interval '1 month')::date LOOP
        IF to_regclass(format('audit_event_%s', to_char(m, 'YYYY_MM'))) IS NULL THEN
            PERFORM create_audit_event_partition(m);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

REVOKE ALL ON FUNCTION ensure_audit_event_partitions(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ensure_audit_event_partitions(int) TO onboarding_app;
