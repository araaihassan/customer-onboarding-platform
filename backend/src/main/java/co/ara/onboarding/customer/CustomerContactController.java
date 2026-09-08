package co.ara.onboarding.customer;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    private static final String FORBIDDEN = "Caller holds no sufficient contact.view / contact.manage / invitation.send grant";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (spec 6.8: identical response either way)";

    private final CustomerContactService contacts;

    public CustomerContactController(CustomerContactService contacts) { this.contacts = contacts; }

    @GetMapping
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All contacts for the customer"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<CustomerContactService.ContactView> list(@PathVariable UUID customerId) {
        return contacts.list(customerId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Contact created"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "A contact with this email already exists for this customer",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
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
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contact updated"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "A contact with this email already exists for this customer",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
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
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Invitation sent to contact"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void invite(@PathVariable UUID contactId) {
        contacts.sendInvitation(contactId);
    }
}
