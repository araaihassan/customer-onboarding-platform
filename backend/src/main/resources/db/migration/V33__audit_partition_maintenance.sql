-- Sub-project 6, spec 6.4, invariant 8. The roll-forward job runs as onboarding_app, which holds
-- no DDL privilege and must not gain one. This function is the one narrow thing it may ask for: it
-- takes a bounded integer, builds no identifier from input, and only calls V27's
-- create_audit_event_partition -- which creates AND hardens (no grant, forced RLS) one month.
-- It is SECURITY DEFINER (owned by the Flyway owner, who may create partitions); search_path is
-- pinned so no schema the caller controls can shadow a name it resolves.
--
-- If DEFAULT ever holds rows for a month about to be created, create_audit_event_partition fails
-- and this whole call rolls back, loudly, in the job log. That is the right failure: V27's
-- relocation is the fix, and a daily job makes it unnecessary.
CREATE OR REPLACE FUNCTION ensure_audit_event_partitions(months_ahead int)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
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
-- create_audit_event_partition stays callable only by its owner and the definer function above.
REVOKE ALL ON FUNCTION create_audit_event_partition(date) FROM PUBLIC;
