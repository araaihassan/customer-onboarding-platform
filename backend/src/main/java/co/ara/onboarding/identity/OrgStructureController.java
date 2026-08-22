package co.ara.onboarding.identity;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/t/{tenantSlug}/admin")
public class OrgStructureController {

    private final OrgStructureService org;

    public OrgStructureController(OrgStructureService org) { this.org = org; }

    @GetMapping("/departments")
    public List<OrgStructureService.DepartmentView> listDepartments() {
        return org.listDepartments();
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public OrgStructureService.DepartmentView createDepartment(
            @RequestBody OrgStructureService.DepartmentRequest request) {
        return org.createDepartment(request);
    }

    @GetMapping("/teams")
    public List<OrgStructureService.TeamView> listTeams() { return org.listTeams(); }

    @PostMapping("/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public OrgStructureService.TeamView createTeam(
            @RequestBody OrgStructureService.TeamRequest request) {
        return org.createTeam(request);
    }

    @GetMapping("/teams/{teamId}/members")
    public List<OrgStructureService.TeamMemberView> members(@PathVariable UUID teamId) {
        return org.listTeamMembers(teamId);
    }

    public record AddMemberRequest(UUID userId) {}

    @PostMapping("/teams/{teamId}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable UUID teamId, @RequestBody AddMemberRequest request) {
        org.addTeamMember(teamId, request.userId());
    }

    @PostMapping("/teams/{teamId}/members/{userId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID teamId, @PathVariable UUID userId) {
        org.removeTeamMember(teamId, userId);
    }
}
