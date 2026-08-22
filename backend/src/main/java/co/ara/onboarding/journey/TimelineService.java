package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditEventView;
import co.ara.onboarding.audit.AuditQuery;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reads a case's audit history. Gated on case.view, not audit.view --
 * AuditEventDescriptor scopes events by ACTOR (DEPARTMENT/TEAM resolve through
 * actorUserId), which is the wrong axis for a case timeline: a Project Manager
 * holding audit.view at TEAM would see only their own teammates' events, so
 * Legal's approval, Finance's verification and every SYSTEM transition would
 * silently vanish from the history of a case they own.
 */
@Service
public class TimelineService {

    private final CaseRepository cases;
    private final AuthorizedQuery authorizedQuery;
    private final AuditQuery audit;

    public TimelineService(CaseRepository cases, AuthorizedQuery authorizedQuery, AuditQuery audit) {
        this.cases = cases;
        this.authorizedQuery = authorizedQuery;
        this.audit = audit;
    }

    /**
     * This line is the authorization for everything below it: out of scope means
     * 404, and no events are read at all. The whole history, not filtered by
     * timeline_visible -- internally a reviewer needs every event, including the
     * compliance-only ones; timeline_visible is what sub-project 7's portal
     * filters on.
     */
    @RequirePermission(PermissionKeys.CASE_VIEW)
    @Transactional(readOnly = true)
    public Page<AuditEventView> forCase(UUID caseId, Pageable pageable) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, caseId);
        return audit.findForResource("onboarding_case", c.getId(), pageable);
    }
}
