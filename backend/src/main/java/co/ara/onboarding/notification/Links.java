package co.ara.onboarding.notification;

import java.util.UUID;

/** In-app link paths a notification points at; relative, since no public base URL exists yet. */
public final class Links {

    private Links() {}

    /** {@code /t/{slug}/customers/{customerId}/cases/{caseId}} */
    public static String caseLink(String slug, UUID customerId, UUID caseId) {
        return "/t/" + slug + "/customers/" + customerId + "/cases/" + caseId;
    }

    /** {@code /t/{slug}/customers/{customerId}} */
    public static String customerLink(String slug, UUID customerId) {
        return "/t/" + slug + "/customers/" + customerId;
    }
}
