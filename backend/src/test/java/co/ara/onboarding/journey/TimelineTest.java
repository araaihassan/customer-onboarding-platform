package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditEvent;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.audit.AuditEventView;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first audit read path in the codebase. Deliberately does not reuse
 * AUDIT_VIEW -- AuditEventDescriptor scopes events by ACTOR, the wrong axis for a
 * case's shared history (TimelineService's own javadoc explains why).
 */
class TimelineTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired MigrationService migrations;
    @Autowired TimelineService timeline;
    @Autowired RoleService roles;
    @Autowired AuditEventRepository auditEvents;

    /**
     * The timeline shows the case's whole history regardless of who acted. Every
     * event recorded through TenantFixture's runAs/runAsUser carries a null actor
     * (JwtAuthenticationFilter is what populates RequestAuditContext in a real
     * request, and these fixtures bypass the filter chain entirely) -- which
     * doubles as the "SYSTEM event" case here, and TEAM-scoped case.view resolves
     * through the CASE's own owningTeamId, never through who performed each event,
     * so this is true regardless.
     */
    @Test
    void theTimelineIncludesEventsByOtherTeamsAndBySystem() {
        UUID tenant = fixture.createTenant("tl-actors");
        var caseId = new UUID[1];
        var pm = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Onboarding Team");
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, team);
            caseId[0] = cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();
            requirements.satisfy(firstRequirementId(caseId[0]), null, null);   // completes the case too

            pm[0] = fixture.createUser(tenant, "pm@example.com");
            fixture.addToTeam(tenant, pm[0], team);
            UUID role = roles.createRole("Fixture PM " + Uuid7.generate(), "",
                    Map.of(PermissionKeys.CASE_VIEW, Scope.TEAM));
            roles.assignRole(pm[0], role);
        });

        fixture.runAsUser(tenant, pm[0], () -> {
            Page<AuditEventView> events = timeline.forCase(caseId[0], Pageable.ofSize(50));
            assertThat(events).extracting(AuditEventView::action)
                    .contains("case.created", "milestone.completed", "case.completed");
            assertThat(events).anySatisfy(e -> assertThat(e.actorUserId()).isNull());
        });
    }

    /** Authorization is the parent resolution: no case, no timeline. */
    @Test
    void aCaseTheActorCannotSeeHasNoTimeline() {
        UUID tenant = fixture.createTenant("tl-outsider");
        var caseId = new UUID[1];
        var outsider = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            caseId[0] = cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();
            outsider[0] = fixture.createUser(tenant, "outsider@example.com");
            UUID role = roles.createRole("Fixture Outsider " + Uuid7.generate(), "",
                    Map.of(PermissionKeys.CASE_VIEW, Scope.ASSIGNED));
            roles.assignRole(outsider[0], role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, outsider[0], () ->
                timeline.forCase(caseId[0], Pageable.ofSize(10))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** The carve-out must not leak: exactly one case's events, never a neighbour's. */
    @Test
    void theTimelineReturnsOnlyThisCasesEvents() {
        UUID tenant = fixture.createTenant("tl-isolation");
        fixture.runAs(tenant, () -> {
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseA = cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();
            UUID caseB = cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();

            Page<AuditEventView> eventsA = timeline.forCase(caseA, Pageable.ofSize(50));
            assertThat(eventsA).isNotEmpty();
            assertThat(eventsA.getContent()).noneMatch(e ->
                    timeline.forCase(caseB, Pageable.ofSize(50)).getContent().stream()
                            .anyMatch(other -> other.id().equals(e.id())));
        });
    }

    @Test
    void anotherTenantsCaseIdHasNoTimeline() {
        UUID tenantA = fixture.createTenant("tl-cross-a");
        UUID tenantB = fixture.createTenant("tl-cross-b");
        var caseId = new UUID[1];
        fixture.runAs(tenantA, () -> {
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenantA, "Acme", null, null, null);
            caseId[0] = cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();
        });

        assertThatThrownBy(() -> fixture.runAs(tenantB, () ->
                timeline.forCase(caseId[0], Pageable.ofSize(10))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void eventsAreNewestFirstWithMonoReadyTimestamps() {
        UUID tenant = fixture.createTenant("tl-order");
        fixture.runAs(tenant, () -> {
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();
            requirements.satisfy(firstRequirementId(caseId), null, null);

            var events = timeline.forCase(caseId, Pageable.ofSize(50)).getContent();
            assertThat(events).hasSizeGreaterThanOrEqualTo(2);

            // Asserts the full (occurredAt, id) contract. Note this alone does
            // NOT prove the tiebreak works: whether these events actually share
            // a timestamp is up to the clock, and a run of *distinct* values
            // satisfies the comparator with or without `id DESC` in the query.
            // eventsSharingATimestampAreOrderedById below forces the tie, and
            // is the test that fails when the tiebreak is removed.
            assertThat(events).isSortedAccordingTo(
                    Comparator.comparing(AuditEventView::occurredAt)
                            .thenComparing(AuditEventView::id, Uuid7::compareUnsigned)
                            .reversed());
        });
    }

    /**
     * The regression test for the ordering bug, and the reason it needs to write
     * audit rows by hand rather than lean on the events the case itself records.
     *
     * AuditRecorder stamps occurred_at with Instant.now(), so several events
     * recorded inside one request -- the normal case, since a single
     * CaseEngine.reconcile can satisfy a requirement, complete a milestone and
     * advance a stage -- can land on an identical timestamp. But whether they
     * actually do is up to the clock's resolution, which makes it useless as the
     * precondition of an assertion: the real events above are only *likely* to
     * tie. Forcing an exact tie is what makes this deterministic.
     *
     * Without `id DESC` in the query, ordering on occurred_at alone leaves tied
     * rows in no defined order at all, and Postgres returns them in heap order --
     * i.e. ascending id, i.e. oldest-first, the exact reverse of what the
     * timeline promises.
     */
    @Test
    void eventsSharingATimestampAreOrderedById() {
        UUID tenant = fixture.createTenant("tl-ties");
        fixture.runAs(tenant, () -> {
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();

            Instant sameMoment = Instant.now();
            List<UUID> inserted = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                inserted.add(appendEvent(tenant, caseId, sameMoment, "case.tied_" + i));
            }

            var ids = timeline.forCase(caseId, Pageable.ofSize(50)).getContent().stream()
                    .map(AuditEventView::id)
                    .filter(inserted::contains)
                    .toList();

            // Uuid7.generate() is strictly monotonic -- a 12-bit per-millisecond
            // counter, spin-waiting rather than carrying into the millis field --
            // so insertion order IS id order, and newest-first over a tied group
            // means exactly the reverse of the order they went in. That is why
            // the tiebreak is *correct* and not merely *stable*.
            assertThat(ids).containsExactlyElementsOf(inserted.reversed());
        });
    }

    /** Mirrors AuditRecorder.record, but with occurredAt supplied rather than clock-read. */
    private UUID appendEvent(UUID tenant, UUID caseId, Instant at, String action) {
        AuditEvent e = new AuditEvent();
        e.setId(Uuid7.generate());
        e.setTenantId(tenant);
        e.setOccurredAt(at);
        e.setAction(action);
        e.setTimelineVisible(true);
        e.setResourceType("onboarding_case");
        e.setResourceId(caseId);
        e.setSummary("tie fixture");
        e.setPayload("{}");
        e.setActorType("SYSTEM");
        auditEvents.save(e);
        return e.getId();
    }

    /**
     * timeline_visible is NOT the internal filter. Internally the timeline is the
     * whole history; the flag is what sub-project 7's portal will filter on, and
     * asserting that here stops someone "fixing" the internal query later.
     */
    @Test
    void theInternalTimelineIncludesComplianceOnlyEvents() {
        UUID tenant = fixture.createTenant("tl-compliance");
        fixture.runAs(tenant, () -> {
            UUID v1 = journey.publish(new co.ara.onboarding.workflow.WorkflowDefinitionRequest(
                    java.util.List.of(co.ara.onboarding.workflow.WorkflowFixtures.stage("s1", "Stage One",
                            java.util.List.of(co.ara.onboarding.workflow.WorkflowFixtures.milestone(
                                    "m1", "Milestone One", 1, java.util.List.of(),
                                    java.util.List.of(co.ara.onboarding.workflow.WorkflowFixtures.manual("Do it")))))),
                    java.util.List.of(), 0L));
            UUID templateId = journey.templateOf(v1);
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();

            UUID v2 = journey.publishNewVersion(templateId, new co.ara.onboarding.workflow.WorkflowDefinitionRequest(
                    java.util.List.of(co.ara.onboarding.workflow.WorkflowFixtures.stage("s1", "Stage One",
                            java.util.List.of(co.ara.onboarding.workflow.WorkflowFixtures.milestone(
                                    "m1", "Milestone One", 2, java.util.List.of(),
                                    java.util.List.of(co.ara.onboarding.workflow.WorkflowFixtures.manual("Do it")))))),
                    java.util.List.of(), 0L));
            migrations.migrate(v2, java.util.List.of(caseId));

            var events = timeline.forCase(caseId, Pageable.ofSize(50));
            assertThat(events).extracting(AuditEventView::action).contains("case.migrated");
        });
    }

    private UUID firstRequirementId(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).requirements().get(0).id();
    }
}
