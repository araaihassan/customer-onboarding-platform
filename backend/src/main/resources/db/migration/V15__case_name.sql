-- Q18: a journey (case) carries a human-readable name, set at creation --
-- "Enterprise onboarding", "EU expansion" -- rather than the stage-plus-id
-- label CaseSwitcher fakes today (currentStageName + a short id chip), which
-- is not a name and stops being right the moment the stage advances.
--
-- Existing rows predate the column and never got a name from anyone -- there
-- is no user-authored value to recover -- so they are backfilled with
-- exactly the same fake label the UI already shows: the workflow template's
-- name plus the case id's own short id (its last hyphen-delimited segment,
-- the same truncation frontend/src/lib/api/customers.ts's shortId() applies)
-- rather than inventing a name that would read as something a person wrote.
-- That is also what CaseService.create falls back to when a caller (today,
-- CreateCaseDialog) does not supply one, so the column can be NOT NULL
-- without requiring every existing client to be updated first.

ALTER TABLE onboarding_case ADD COLUMN name varchar(160);

UPDATE onboarding_case c
SET name = t.name || ' ' || split_part(c.id::text, '-', 5)
FROM workflow_template t
WHERE c.template_id = t.id;

ALTER TABLE onboarding_case ALTER COLUMN name SET NOT NULL;
