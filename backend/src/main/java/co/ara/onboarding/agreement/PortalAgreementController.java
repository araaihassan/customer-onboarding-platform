package co.ara.onboarding.agreement;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The customer portal's read-only agreement endpoints; a non-portal caller gets 404 from the service. */
@RestController
@RequestMapping("/api/t/{tenantSlug}/portal/agreements")
public class PortalAgreementController {

    private final PortalAgreementService portal;

    public PortalAgreementController(PortalAgreementService portal) {
        this.portal = portal;
    }

    @GetMapping
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The portal contact's own customer's agreements, SENT onward"),
            @ApiResponse(responseCode = "403", description = "Caller holds no sufficient grant",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The caller is not a portal user",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<PortalAgreementView> mine() {
        return portal.mine();
    }

    @GetMapping("/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One agreement as the portal sees it"),
            @ApiResponse(responseCode = "403", description = "Caller holds no sufficient grant",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Absent, hidden from the portal, or the caller is not a portal user",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PortalAgreementView get(@PathVariable UUID id) {
        return portal.get(id);
    }
}
