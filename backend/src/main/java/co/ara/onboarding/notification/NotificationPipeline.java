package co.ara.onboarding.notification;

import co.ara.onboarding.authz.RecipientAccess;
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
 */
@Component
public class NotificationPipeline {

    public record Draft(NotificationType type, String subjectType, UUID subjectId, UUID caseId, String title,
                        String body, String linkPath, Tone tone, String dedupeKey) {}

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
            var pref = prefs.resolve(recipient, draft.type());                                  // step 4
            if (!pref.inApp() && !pref.email()) continue;
            EmailState state = !pref.email() ? EmailState.NONE
                    : pref.cadence() == EmailCadence.IMMEDIATE ? EmailState.QUEUED : EmailState.DIGEST_PENDING;
            if (writer.write(draft, recipient, pref.inApp(), state, email.get()).isPresent()) written++; // step 6
        }
        return written;
    }

    /** Spec 6.2: records a consumed dedupe key (a lead time already used) without notifying anyone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Draft draft, UUID recipientUserId) {
        if (draft.dedupeKey() == null) throw new IllegalArgumentException("A marker needs a dedupe key");
        writer.writeMarker(draft, recipientUserId);
    }

    /**
     * As {@link #consume(Draft, UUID)}, but only for a recipient who could still be notified about the subject:
     * a marker carries the draft's title and body, so one written for someone outside the subject's audience
     * (a portal user, an out-of-scope owner) would store text they may not see.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Draft draft, UUID recipientUserId, Visibility visibility) {
        if (recipientUserId == null || facts.activeInternalEmail(recipientUserId).isEmpty()) return;
        if (!access.canView(recipientUserId, visibility.permissionKey(), visibility.entityType(), visibility.id())) return;
        consume(draft, recipientUserId);
    }
}
