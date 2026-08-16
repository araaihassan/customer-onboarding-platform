package co.ara.onboarding.customer;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/t/{tenantSlug}/customers/{customerId}/contacts")
public class CustomerContactController {

    private final CustomerContactService contacts;

    public CustomerContactController(CustomerContactService contacts) { this.contacts = contacts; }

    @GetMapping
    public List<CustomerContactService.ContactView> list(@PathVariable UUID customerId) {
        return contacts.list(customerId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerContactService.ContactView create(
            @PathVariable UUID customerId,
            @RequestBody CustomerContactService.CreateContactRequest request) {
        return contacts.create(customerId, request);
    }

    /**
     * customerId is bound and passed through, not ignored. The service checks the
     * contact actually hangs off it and answers 404 otherwise — a path variable
     * nothing verifies is one a later caller will assume means something.
     */
    @PutMapping("/{contactId}")
    public CustomerContactService.ContactView update(
            @PathVariable UUID customerId,
            @PathVariable UUID contactId,
            @RequestBody CustomerContactService.UpdateContactRequest request) {
        return contacts.update(customerId, contactId, request);
    }

    /**
     * 204 with no body: the raw activation token goes to the contact by email and
     * must not be returned to the caller, who would then hold a credential for
     * someone else's account.
     */
    @PostMapping("/{contactId}/invitations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void invite(@PathVariable UUID contactId) {
        contacts.sendInvitation(contactId);
    }
}
