package co.ara.onboarding.notification;

import java.util.UUID;

/** In-app link paths a notification points at; relative, since no public base URL exists yet. */
public final class Links {

    private Links() {}

    /** {@code /t/{slug}/customers/{customerId}/cases/{caseId}} */
    public static String caseLink(String slug, UUID customerId, UUID caseId) {
        return "/t/" + slug + "/customers/" + customerId + "/cases/" + caseId;
    }

    /** {@code /t/{slug}/work} -- the cross-case "My work" board, readable under task.view alone. */
    public static String workLink(String slug) {
        return "/t/" + slug + "/work";
    }

    /** {@code /t/{slug}/documents} -- the tenant-wide documents index, readable under document.view alone. */
    public static String documentsLink(String slug) {
        return "/t/" + slug + "/documents";
    }

    /** {@code /t/{slug}/agreements} -- the tenant-wide agreements screen, readable under agreement.view alone. */
    public static String agreementsLink(String slug) {
        return "/t/" + slug + "/agreements";
    }

    /** {@code /t/{slug}/customers/{customerId}} */
    public static String customerLink(String slug, UUID customerId) {
        return "/t/" + slug + "/customers/" + customerId;
    }
}
