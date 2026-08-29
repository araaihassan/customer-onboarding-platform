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
-- This backfill is the ONLY place that synthesis survives (fix round 1 of
-- Task 10's review removed CaseService.create's own copy of it -- a name
-- fabricated at create time was worse than the bug Q18 exists to close: it
-- reads as real but is identical across every case opened from the same
-- template, sitting right next to an id the switcher already shows in its
-- mono chip). Every new case gets a real, caller-supplied name instead
-- (CreateCaseRequest.name is @NotBlank); only historical rows still need
-- this fallback.
--
-- workflow_template.name is varchar(160) with no length validation at the
-- application layer, so a template name near that limit plus this suffix
-- (a space and a 12-hex-character short id, 13 characters) can overflow
-- name's own varchar(160) -- left(...) truncates the constructed value to
-- fit rather than letting a legal template name fail this forward-only
-- migration outright (which would leave the application refusing to start).

ALTER TABLE onboarding_case ADD COLUMN name varchar(160);

UPDATE onboarding_case c
SET name = left(t.name || ' ' || split_part(c.id::text, '-', 5), 160)
FROM workflow_template t
WHERE c.template_id = t.id;

ALTER TABLE onboarding_case ALTER COLUMN name SET NOT NULL;
