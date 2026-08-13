package co.ara.onboarding.auth;

/**
 * Deliberately narrow — one method, no templates, no scheduling, no preferences.
 *
 * The full notification system lands in sub-project 6, and this interface exists so
 * that system does not leak backwards into this one. Anything richer here becomes a
 * contract sub-project 6 has to honour or break.
 */
public interface EmailSender {
    void send(EmailMessage message);
}
