package co.ara.onboarding.customer;

import co.ara.onboarding.identity.DepartmentRepository;
import co.ara.onboarding.identity.TeamRepository;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Resolves department and team ids from customer ownership requests.
 *
 * Department and team lookups bypass AuthorizedQuery because no DEPARTMENT_VIEW
 * or TEAM_VIEW permissions exist — only DEPARTMENT_MANAGE and TEAM_MANAGE,
 * both ALL-only administrative permissions. Tenancy isolation is provided by
 * Hibernate's automatic @Filter on TenantScopedEntity (applied at query time)
 * and PostgreSQL row-level security (enforced at constraint time), both of which
 * apply to all repository.findById() queries in a tenant-bound session.
 *
 * This class is exempted from AuthorizationCoverageTest's
 * servicesDoNotCallRepositoryFindersDirectly rule for this same reason: the
 * rule requires scope-checked reads go through AuthorizedQuery, but these reads
 * have no scope to check — they query administrative infrastructure, not
 * business records.
 */
@Component
public class OrgUnitResolver {

    private final DepartmentRepository departments;
    private final TeamRepository teams;

    public OrgUnitResolver(DepartmentRepository departments, TeamRepository teams) {
        this.departments = departments;
        this.teams = teams;
    }

    public UUID resolveDepartment(UUID departmentId) {
        if (departmentId == null) return null;
        return departments.findById(departmentId)
                .orElseThrow(() -> new NoSuchElementException("Department not found"))
                .getId();
    }

    public UUID resolveTeam(UUID teamId) {
        if (teamId == null) return null;
        return teams.findById(teamId)
                .orElseThrow(() -> new NoSuchElementException("Team not found"))
                .getId();
    }
}
