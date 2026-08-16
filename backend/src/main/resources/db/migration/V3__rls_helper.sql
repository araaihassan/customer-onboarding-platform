-- Applies the standard tenant isolation policy to a table.
-- FORCE is required so the policy also applies to the table owner.
CREATE OR REPLACE FUNCTION enable_tenant_rls(target_table text)
RETURNS void AS $$
BEGIN
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', target_table);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', target_table);
    -- current_setting('app.tenant_id', true) returns NULL when the GUC was
    -- never set in this session, but PostgreSQL resets a custom (placeholder)
    -- GUC to '' -- not NULL -- on RESET, and ''::uuid raises a cast error
    -- rather than failing closed. nullif(...,'') collapses both "never set"
    -- and "reset" to NULL so tenant_id = NULL (never true) is what actually
    -- runs in both cases -- fail-closed without an exception.
    EXECUTE format($f$
        CREATE POLICY tenant_isolation ON %I
        USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    $f$, target_table);
END;
$$ LANGUAGE plpgsql;
