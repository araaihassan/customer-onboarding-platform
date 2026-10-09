package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentRequested;
import co.ara.onboarding.document.DocumentReviewed;
import co.ara.onboarding.document.DocumentUploaded;
import co.ara.onboarding.journey.Case;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * DOCUMENT_REQUESTED, DOCUMENT_UPLOADED and DOCUMENT_DECIDED (spec 5.2).
 *
 * <p>A request is gated case.view (the request itself carries no audience). An upload and a
 * decision are gated document.view on the document, so RecipientAccess applies the document
 * audience filter (department targeting and shares) to every recipient, at ALL scope included --
 * a recipient a targeted document is hidden from is never told it exists (Review Focus 1).
 *
 * <p>The upload's dedupe key {@code UPLOADED:{documentId}} exists only to collapse the double
 * publish -- the portal upload and then the staff fulfil of a request with that same document
 * -- into one row per recipient. It is not a sweep reminder key.
 */
@Component
public class DocumentNotifications {

    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;

    DocumentNotifications(NotificationPipeline pipeline, SubjectFacts facts) {
        this.pipeline = pipeline;
        this.facts = facts;
    }

    @EventListener
    public void on(DocumentRequested e) {
        var req = facts.documentRequest(e.requestId());
        var kase = facts.caseFacts(e.caseId());
        String what = humanise(req.category()) + (req.description() == null ? "" : " - " + Text.clip(req.description(), 120));
        var draft = new NotificationPipeline.Draft(NotificationType.DOCUMENT_REQUESTED, "document_request", req.id(),
                kase.id(), "Document requested on " + Text.clip(kase.name(), 80), what,
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.INFO, null);
        pipeline.deliver(draft, kase.ownerUserId() == null ? List.of() : List.of(kase.ownerUserId()), e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, kase.id()));
    }

    @EventListener
    public void on(DocumentUploaded e) {
        var doc = facts.document(e.documentId());
        var kase = facts.caseFacts(e.caseId());
        Set<UUID> candidates = new LinkedHashSet<>();
        if (e.requestId() != null) candidates.add(facts.documentRequest(e.requestId()).requestedBy());
        else facts.requesterOfDocument(doc.id()).ifPresent(candidates::add);
        if (kase.ownerUserId() != null) candidates.add(kase.ownerUserId());
        var draft = new NotificationPipeline.Draft(NotificationType.DOCUMENT_UPLOADED, "document", doc.id(), kase.id(),
                kase.customerName() + " uploaded a document", Text.clip(doc.name(), 120) + " on " + kase.name() + ".",
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.INFO,
                "UPLOADED:" + doc.id());   // the portal upload and the fulfil both publish; deliver once
        pipeline.deliver(draft, candidates, e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.DOCUMENT_VIEW, Document.class, doc.id()));
    }

    @EventListener
    public void on(DocumentReviewed e) {
        var doc = facts.document(e.documentId());
        var kase = facts.caseFacts(e.caseId());
        boolean approved = "APPROVED".equals(e.decision());
        Set<UUID> candidates = new LinkedHashSet<>();
        facts.requesterOfDocument(doc.id()).ifPresent(candidates::add);
        if (kase.ownerUserId() != null) candidates.add(kase.ownerUserId());
        facts.internalVersionUploader(doc.id(), e.versionNo()).ifPresent(candidates::add);
        var draft = new NotificationPipeline.Draft(NotificationType.DOCUMENT_DECIDED, "document", doc.id(), kase.id(),
                Text.clip(doc.name(), 80) + (approved ? " approved" : " rejected"),
                "Version " + e.versionNo() + " on " + kase.name() + ".",
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), approved ? Tone.OK : Tone.RISK, null);
        pipeline.deliver(draft, candidates, e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.DOCUMENT_VIEW, Document.class, doc.id()));
    }

    /** "COMPANY_REGISTRATION" -> "Company registration": never the raw enum in a message (sub-project 6's open item). */
    static String humanise(String category) {
        if (category == null || category.isBlank()) return "Document";
        String s = category.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
