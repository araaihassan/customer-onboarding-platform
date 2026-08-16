package co.ara.onboarding.identity;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Departments and teams.
 *
 * department.manage and team.manage are ALL-only permissions, so these reads have
 * no record-level scope to apply — but they still go through AuthorizedQuery rather
 * than the repository. Two reasons: the pattern should not vary by whether a
 * permission happens to be ALL-only today, and AuthorizationPredicateBuilder
 * short-circuits ALL before touching the descriptor registry, so neither entity
 * needs a descriptor for this to work.
 */
@Service
public class OrgStructureService {

    public record DepartmentRequest(String name, String description) {}
    public record DepartmentView(UUID id, String name, String description) {}

    public record TeamRequest(String name, String description, UUID departmentId) {}
    public record TeamView(UUID id, String name, String description, UUID departmentId) {}

    private static final Specification<Department> ALL_DEPARTMENTS =
            (root, query, cb) -> cb.conjunction();
    private static final Specification<Team> ALL_TEAMS =
            (root, query, cb) -> cb.conjunction();

    private final DepartmentRepository departments;
    private final TeamRepository teams;
    private final AuthorizedQuery authorizedQuery;
    private final AuditRecorder audit;

    public OrgStructureService(DepartmentRepository departments, TeamRepository teams,
                               AuthorizedQuery authorizedQuery, AuditRecorder audit) {
        this.departments = departments;
        this.teams = teams;
        this.authorizedQuery = authorizedQuery;
        this.audit = audit;
    }

    @RequirePermission(PermissionKeys.DEPARTMENT_MANAGE)
    @Transactional(readOnly = true)
    public List<DepartmentView> listDepartments() {
        return authorizedQuery.findAll(departments, Department.class,
                        PermissionKeys.DEPARTMENT_MANAGE, ALL_DEPARTMENTS, Pageable.unpaged())
                .map(d -> new DepartmentView(d.getId(), d.getName(), d.getDescription()))
                .getContent();
    }

    @RequirePermission(PermissionKeys.DEPARTMENT_MANAGE)
    @Transactional
    public DepartmentView createDepartment(DepartmentRequest request) {
        Department d = new Department();
        d.setId(Uuid7.generate());
        d.setTenantId(TenantContext.getRequired());
        d.setName(request.name());
        d.setDescription(request.description());
        departments.save(d);

        audit.record(AuditActions.DEPARTMENT_CREATED, "department", d.getId(),
                "Created department " + d.getName(), Map.of());
        return new DepartmentView(d.getId(), d.getName(), d.getDescription());
    }

    @RequirePermission(PermissionKeys.TEAM_MANAGE)
    @Transactional(readOnly = true)
    public List<TeamView> listTeams() {
        return authorizedQuery.findAll(teams, Team.class,
                        PermissionKeys.TEAM_MANAGE, ALL_TEAMS, Pageable.unpaged())
                .map(t -> new TeamView(t.getId(), t.getName(), t.getDescription(), t.getDepartmentId()))
                .getContent();
    }

    @RequirePermission(PermissionKeys.TEAM_MANAGE)
    @Transactional
    public TeamView createTeam(TeamRequest request) {
        Team t = new Team();
        t.setId(Uuid7.generate());
        t.setTenantId(TenantContext.getRequired());
        t.setName(request.name());
        t.setDescription(request.description());
        t.setDepartmentId(request.departmentId());
        teams.save(t);

        // departmentId is on the payload rather than the summary: it is the only
        // thing that distinguishes two teams with the same name, and a scope
        // question later ("which department could reach this?") is answered by it.
        audit.record(AuditActions.TEAM_CREATED, "team", t.getId(),
                "Created team " + t.getName(),
                t.getDepartmentId() == null ? Map.of()
                        : Map.of("departmentId", t.getDepartmentId().toString()));
        return new TeamView(t.getId(), t.getName(), t.getDescription(), t.getDepartmentId());
    }
}
