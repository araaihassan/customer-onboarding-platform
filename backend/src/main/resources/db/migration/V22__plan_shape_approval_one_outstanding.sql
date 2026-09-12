-- Final whole-branch review (sub-project 3A), finding #5: plan_shape_approval had
-- no equivalent of plan_revision's "at most one outstanding" partial unique index
-- (V21, plan_revision_one_outstanding_uq) -- so a second PlanShapeService.submit
-- call for the same version silently stranded the first SUBMITTED row forever
-- (never resolved, never visible again, since currentRow/currentApproval always
-- pick the newest row by submitted_at). Mirrors that index exactly, scoped by
-- version_id rather than case_id: gate 1 approves the shape of a VERSION, not a
-- per-journey schedule, so version_id is this table's equivalent of case_id.
CREATE UNIQUE INDEX plan_shape_approval_one_outstanding_uq
    ON plan_shape_approval (version_id) WHERE status = 'SUBMITTED';
