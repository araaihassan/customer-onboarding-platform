package co.ara.onboarding.task;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.RequirementKind;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Creates a {@link Task} row for every requirement of kind TASK on a freshly
 * opened case -- the seam sub-project 2 deliberately left as a plain manual
 * check-off. Deliberately not named *Service or *Directory:
 * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly binds
 * only to those suffixes, and this class is called by {@link
 * TaskLifecycleAdapter} from inside {@code CaseService.create}'s own
 * transaction, on a {@code caseId} that method just created and fully
 * controls in the same call -- there is no request-supplied id here for
 * {@code AuthorizedQuery} to protect against, unlike {@link TaskService},
 * which IS reachable from a controller and does route every id through it.
 * The gate already happened at whatever public {@code *Service} method
 * triggered case creation.
 *
 * Nothing is copied from the requirement definition beyond its label and
 * kind -- same reasoning as {@code CaseService.instantiate}'s own milestone
 * and requirement rows: instances join their definitions, copying drifts.
 *
 * Task 25 adds {@code AuditActions.TASK_CREATED} recording, one event per
 * instantiated task. This class runs strictly between {@code CaseService
 * .create}'s own {@code CASE_CREATED} record and {@code engine.reconcile}
 * (see that call site) -- so every event recorded here lands after the
 * case's own creation and before anything the engine's own reconcile might
 * itself cause, satisfying CauseBeforeEffectTest's cause-before-effect rule
 * by construction rather than by a check added here. Task 19 (which built
 * this class) was deliberately told not to record anything: nothing in its
 * own scope needed it, and the design spec named task.created as a future
 * action for whichever task actually needed it first -- this one.
 */
@Component
public class TaskInstantiation {

    private final RequirementRepository requirements;
    private final RequirementDefinitionRepository requirementDefinitions;
    private final MilestoneRepository milestones;
    private final TaskRepository tasks;
    private final AuditRecorder audit;

    public TaskInstantiation(RequirementRepository requirements,
                              RequirementDefinitionRepository requirementDefinitions,
                              MilestoneRepository milestones, TaskRepository tasks,
                              AuditRecorder audit) {
        this.requirements = requirements;
        this.requirementDefinitions = requirementDefinitions;
        this.milestones = milestones;
        this.tasks = tasks;
        this.audit = audit;
    }

    /**
     * Every requirement on the case is inspected; only ones whose definition
     * is kind TASK produce a row. Q15: assigneeId defaults to the milestone's
     * own ownerUserId (already set to the case's default owner by
     * CaseService.instantiate, which runs before this is ever called) -- an
     * instantiated task arriving unassigned would put work in nobody's queue
     * at the exact moment the journey opens.
     */
    public void instantiateForCase(UUID caseId) {
        for (Requirement r : requirements.findByCaseId(caseId)) {
            RequirementDefinition definition = requirementDefinitions.findById(r.getRequirementDefinitionId())
                    .orElseThrow(() -> new NoSuchElementException("Not found"));
            if (definition.getKind() != RequirementKind.TASK) {
                continue;
            }

            Milestone m = milestones.findById(r.getMilestoneId())
                    .orElseThrow(() -> new NoSuchElementException("Not found"));

            Task t = new Task();
            t.setId(Uuid7.generate());
            t.setTenantId(r.getTenantId());
            t.setCaseId(r.getCaseId());
            t.setMilestoneId(r.getMilestoneId());
            t.setRequirementId(r.getId());
            t.setTitle(definition.getLabel());
            t.setPriority(TaskPriority.MEDIUM);
            t.setStatus(TaskStatus.PENDING);
            t.setAssigneeId(m.getOwnerUserId());
            tasks.save(t);

            audit.record(AuditActions.TASK_CREATED, "onboarding_case", caseId,
                    "Instantiated task \"" + t.getTitle() + "\" from requirement",
                    Map.of("taskId", t.getId().toString(), "milestoneId", m.getId().toString()));
        }
    }
}
