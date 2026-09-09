-- Sub-project 3A, Task 15: a catalogue template may be cloned for one customer and
-- tailored for them alone (QA Q21). NULL customer_id = the tenant catalogue, which
-- is every template that exists today.
ALTER TABLE workflow_template ADD COLUMN customer_id             uuid NULL REFERENCES customer(id);
ALTER TABLE workflow_template ADD COLUMN cloned_from_template_id uuid NULL REFERENCES workflow_template(id);

-- Provenance, NOT a propagation path: nothing follows this pointer to push an edit
-- downstream. It exists so a clone can be REFRESHED from its source (Task 17) and so
-- Q21's "one clone per customer" has something to enforce against. Without it there
-- is no path from a clone back to its catalogue template at all, and QA Q21's
-- original claim that migration bridges them is false -- MigrationService filters by
-- template_id, so it only ever moves a case between versions of the SAME template.
CREATE UNIQUE INDEX workflow_template_customer_clone_uq
    ON workflow_template (cloned_from_template_id, customer_id)
    WHERE customer_id IS NOT NULL;

CREATE INDEX workflow_template_customer_idx
    ON workflow_template (tenant_id, customer_id) WHERE customer_id IS NOT NULL;
