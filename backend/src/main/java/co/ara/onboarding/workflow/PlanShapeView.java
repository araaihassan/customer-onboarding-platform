package co.ara.onboarding.workflow;

import java.util.List;

/**
 * The whole gate-1 artifact for one {@link WorkflowVersion} -- the approval state and the
 * portal-visible rendering of the shape it applies to, travelling together in ONE
 * response. Never split into two calls: a client that fetched them separately could show
 * an approval next to a plan that has since moved (a new draft's version, or simply the
 * wrong versionId), which is exactly what a shape approval promises never happens to a
 * PUBLISHED version.
 *
 * {@code approval} is {@code null}, not {@link java.util.Optional}, when no submission
 * has ever happened for this version -- {@link PlanShapeService#currentApproval} already
 * returns {@code Optional<PlanShapeApprovalView>} for exactly that reason, and this type
 * unwraps it at the one place a JSON response is built. {@code Optional} as a record
 * component serializes surprisingly (Jackson has no first-class support and most
 * configurations either reject it or wrap it in another object) and buys nothing a plain
 * nullable field does not already give a client checking "has this been submitted yet".
 */
public record PlanShapeView(PlanShapeApprovalView approval, List<PlanShapeStageView> stages) {}
