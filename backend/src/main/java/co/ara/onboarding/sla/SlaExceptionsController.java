package co.ara.onboarding.sla;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SlaExceptionsController {

    private final SlaExceptionsService service;

    public SlaExceptionsController(SlaExceptionsService service) { this.service = service; }

    @GetMapping("/api/t/{tenantSlug}/sla/exceptions")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Breached, due-today and watch clocks within the caller's sla.view scope"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks sla.view")
    })
    public ExceptionsView exceptions() { return service.exceptions(); }
}
