package co.ara.onboarding.journey;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Exposes {@link CaseWeight}s for {@code programme}'s duration-weighted rollup
 * (sub-project 3A, QA Q20), without {@code programme} ever reaching into
 * {@code workflow} for {@link MilestoneDefinition} itself -- {@code journey}
 * already depends on {@code workflow} and can expose the number,
 * {@code ModuleBoundaryTest.noCustomerDependencyOnProgramme}'s sibling rules
 * being the reason a fresh {@code programme -> workflow} dependency is worth
 * avoiding here too.
 *
 * <b>Every read goes through {@link AuthorizedQuery}</b>, never a raw
 * repository finder -- the same discipline every other class in this package
 * follows (see {@code CaseService.readCaseChild}/{@code readDefinition}, the
 * identical pattern this class mirrors): {@code cases} is filtered under
 * {@code case.view} first, so a case id the caller cannot see is silently
 * absent from the result, never an exception and never a row. Only the
 * SURVIVING case ids are then used to read their milestones (also under
 * {@code case.view}, the same permission that scopes {@code Milestone} through
 * {@code scoping.MilestoneDescriptor}) and those milestones' definitions
 * (under {@code workflow.view}, the same permission {@code CaseService}'s own
 * {@code readDefinition} helper uses for the identical entity type) -- so a
 * caller holding {@code case.view} but not {@code workflow.view} gets a
 * durationless (zero-weight) case rather than a definition read that bypasses
 * scope, the same cross-permission shape CLAUDE.md already documents for
 * {@code CaseService}'s own {@code currentStageName}/{@code workflow.view}
 * dependency.
 *
 * The {@code @RequirePermission(CASE_VIEW)} gate is a coarse yes/no ("holds
 * case.view at ANY scope") on top of the record-level filtering above --
 * callers must never invoke this with an empty {@code caseIds} collection on
 * behalf of an actor who might hold no {@code case.view} grant at all, since
 * the gate runs before the method body's own empty-collection short-circuit
 * ever executes. {@code ProgrammeService.get} avoids this by never calling
 * here when {@code journeysFor} already found zero visible journeys.
 */
@Component
public class CaseWeightReader {

    private final CaseRepository cases;
    private final MilestoneRepository milestones;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final AuthorizedQuery authorizedQuery;

    public CaseWeightReader(CaseRepository cases, MilestoneRepository milestones,
                            MilestoneDefinitionRepository milestoneDefinitions,
                            AuthorizedQuery authorizedQuery) {
        this.cases = cases;
        this.milestones = milestones;
        this.milestoneDefinitions = milestoneDefinitions;
        this.authorizedQuery = authorizedQuery;
    }

    @RequirePermission(PermissionKeys.CASE_VIEW)
    public List<CaseWeight> weightsFor(Collection<UUID> caseIds) {
        if (caseIds.isEmpty()) {
            return List.of();
        }

        Specification<Case> byIds = (root, query, cb) -> root.get("id").in(caseIds);
        List<Case> visibleCases = authorizedQuery.findAll(
                        cases, Case.class, PermissionKeys.CASE_VIEW, byIds, Pageable.unpaged())
                .getContent();

        if (visibleCases.isEmpty()) {
            return List.of();
        }
        List<UUID> visibleCaseIds = visibleCases.stream().map(Case::getId).toList();

        // Every non-SKIPPED milestone on a visible case -- SKIPPED is excluded
        // from both halves, exactly CaseEngine.progressOf's own comment: leaving
        // it in the denominator would make 100% unreachable for a case that ever
        // skipped a stage.
        Specification<Milestone> nonSkippedOnVisibleCases = (root, query, cb) -> cb.and(
                root.get("caseId").in(visibleCaseIds),
                cb.notEqual(root.get("status"), MilestoneStatus.SKIPPED));
        List<Milestone> milestoneRows = authorizedQuery.findAll(
                        milestones, Milestone.class, PermissionKeys.CASE_VIEW, nonSkippedOnVisibleCases,
                        Pageable.unpaged())
                .getContent();

        Map<UUID, Integer> durationByDefinitionId = durationsFor(milestoneRows);

        Map<UUID, Integer> weightDaysByCaseId = new HashMap<>();
        for (Milestone m : milestoneRows) {
            int duration = durationByDefinitionId.getOrDefault(m.getMilestoneDefinitionId(), 0);
            weightDaysByCaseId.merge(m.getCaseId(), duration, Integer::sum);
        }

        return visibleCases.stream()
                .map(c -> new CaseWeight(c.getId(), c.getProgressPercent(),
                        weightDaysByCaseId.getOrDefault(c.getId(), 0)))
                .toList();
    }

    /** One AuthorizedQuery call for every distinct milestone_definition id, never per-milestone. */
    private Map<UUID, Integer> durationsFor(List<Milestone> milestoneRows) {
        List<UUID> definitionIds = milestoneRows.stream()
                .map(Milestone::getMilestoneDefinitionId).distinct().toList();
        if (definitionIds.isEmpty()) {
            return Map.of();
        }

        Specification<MilestoneDefinition> byIds = (root, query, cb) -> root.get("id").in(definitionIds);
        return authorizedQuery.findAll(milestoneDefinitions, MilestoneDefinition.class,
                        PermissionKeys.WORKFLOW_VIEW, byIds, Pageable.unpaged())
                .getContent().stream()
                .collect(Collectors.toMap(MilestoneDefinition::getId, MilestoneDefinition::getEstimatedDurationDays));
    }
}
