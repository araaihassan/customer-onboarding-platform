-- Sub-project 3A, Task 18: the milestone-level twin of stage.portal_visible (QA Q24).
-- milestone_definition is a frozen-child table: refuse_published_child_write already
-- refuses every UPDATE whose parent version is not DRAFT, so this flag is authorable
-- only while a version is a draft. Every row that predates this migration takes TRUE
-- permanently -- which is correct, not a gap: retro-hiding a milestone inside a shape
-- a customer already approved is exactly what the freeze exists to prevent.
ALTER TABLE milestone_definition
    ADD COLUMN portal_visible boolean NOT NULL DEFAULT true;
