package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RecipientAccess;
import co.ara.onboarding.journey.Case;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;

/**
 * 6B spec 5.3, steps 1-6, for every type but ESCALATION. Runs inside the producer's (or the
 * sweep's) transaction, so a notification commits or rolls back with its cause (invariant 3).
 *
 * <p>Per candidate: the actor and anyone not an ACTIVE INTERNAL user are dropped (step 2); a
 * recipient who cannot view the subject under their OWN grants is dropped (step 3, RecipientAccess,
 * plan amendment 4); preferences route each channel and both off writes nothing (step 4); the row,
 * its outbox email and its audit event are written together, and a dedupe-key conflict writes
 * none of them (step 6).
 *
 * <p>Case detail (final-review Important 1): a draft that names a case but is gated on something
 * other than that case (a task, a document, an agreement) can reach a recipient who may see the
 * subject but not the case. Such a recipient receives the draft's {@link Draft#withoutCase} text
 * instead -- no case or customer name, and a link to a screen their own gate opens rather than a
 * case page that would 404 -- and a draft with no such variant is not delivered to them at all
 * (fail closed). The text chosen is the text stored for that recipient, so the in-app row, its
 * immediate email and any later digest all carry the same words.
 */
@Component
public class NotificationPipeline {

    public record Draft(NotificationType type, String subjectType, UUID subjectId, UUID caseId, String title,
                        String body, String linkPath, Tone tone, String dedupeKey, WithoutCase withoutCase) {

        public Draft(NotificationType type, String subjectType, UUID subjectId, UUID caseId, String title,
                     String body, String linkPath, Tone tone, String dedupeKey) {
            this(type, subjectType, subjectId, caseId, title, body, linkPath, tone, dedupeKey, null);
        }

        /** This draft, carrying the text a recipient who cannot view {@link #caseId} receives instead. */
        public Draft orWithoutCase(String title, String body, String linkPath) {
            return new Draft(type, subjectType, subjectId, caseId, this.title, this.body, this.linkPath, tone,
                    dedupeKey, new WithoutCase(title, body, linkPath));
        }
    }

    /** A draft's text for a recipient who may see its subject but not its case: no case or customer name. */
    public record WithoutCase(String title, String body, String linkPath) {}

    public record Visibility(String permissionKey, Class<?> entityType, UUID id) {}

    private final RecipientAccess access;
    private final PreferenceReader prefs;
    private final NotificationWriter writer;
    private final SubjectFacts facts;

    NotificationPipeline(RecipientAccess access, PreferenceReader prefs, NotificationWriter writer, SubjectFacts facts) {
        this.access = access;
        this.prefs = prefs;
        this.writer = writer;
        this.facts = facts;
    }

    /** Returns how many notification rows were written. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deliver(Draft draft, Collection<UUID> candidates, UUID actorId, Visibility visibility) {
        if (draft.type() == NotificationType.ESCALATION) {
            throw new IllegalArgumentException("Escalations are written by NotificationWriter.escalation");
        }
        int written = 0;
        for (UUID recipient : new LinkedHashSet<>(candidates)) {
            if (recipient == null || recipient.equals(actorId)) continue;                       // step 2
            Optional<String> email = facts.activeInternalEmail(recipient);
            if (email.isEmpty()) continue;                                                      // step 2
            if (!access.canView(recipient, visibility.permissionKey(), visibility.entityType(), visibility.id())) continue; // step 3
            Optional<Draft> text = forRecipient(draft, recipient, visibility);                  // case detail
            if (text.isEmpty()) continue;
            var pref = prefs.resolve(recipient, draft.type());                                  // step 4
            if (!pref.inApp() && !pref.email()) continue;
            EmailState state = !pref.email() ? EmailState.NONE
                    : pref.cadence() == EmailCadence.IMMEDIATE ? EmailState.QUEUED : EmailState.DIGEST_PENDING;
            if (writer.write(text.get(), recipient, pref.inApp(), state, email.get()).isPresent()) written++; // step 6
        }
        return written;
    }

    /**
     * Spec 6.2: records a consumed dedupe key (a lead time already used) without notifying anyone, and
     * only for a recipient who could still be notified about the subject: a marker carries the draft's
     * title and body, so one written for someone outside the subject's audience (a portal user, an
     * out-of-scope owner) would store text they may not see -- and, for the same reason, a recipient who
     * cannot view the case gets the case-free text.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Draft draft, UUID recipientUserId, Visibility visibility) {
        if (draft.dedupeKey() == null) throw new IllegalArgumentException("A marker needs a dedupe key");
        if (recipientUserId == null || facts.activeInternalEmail(recipientUserId).isEmpty()) return;
        if (!access.canView(recipientUserId, visibility.permissionKey(), visibility.entityType(), visibility.id())) return;
        forRecipient(draft, recipientUserId, visibility).ifPresent(text -> writer.writeMarker(text, recipientUserId));
    }

    /**
     * The draft as this recipient may read it: unchanged when it names no case, was already gated on
     * that case, or the recipient can view the case; its case-free variant otherwise; and empty -- not
     * delivered -- when it has none.
     */
    private Optional<Draft> forRecipient(Draft d, UUID recipient, Visibility visibility) {
        if (d.caseId() == null) return Optional.of(d);
        boolean gatedOnTheCase = PermissionKeys.CASE_VIEW.equals(visibility.permissionKey())
                && visibility.entityType() == Case.class && d.caseId().equals(visibility.id());
        if (gatedOnTheCase || access.canView(recipient, PermissionKeys.CASE_VIEW, Case.class, d.caseId())) {
            return Optional.of(d);
        }
        WithoutCase w = d.withoutCase();
        if (w == null) return Optional.empty();
        return Optional.of(new Draft(d.type(), d.subjectType(), d.subjectId(), d.caseId(), w.title(), w.body(),
                w.linkPath(), d.tone(), d.dedupeKey(), null));
    }
}
