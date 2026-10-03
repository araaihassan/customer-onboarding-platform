package co.ara.onboarding.sla;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/t/{tenantSlug}")
public class SlaClockController {

    private final SlaClockService service;

    public SlaClockController(SlaClockService service) { this.service = service; }

    @GetMapping("/cases/{id}/sla-clock")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The case's current (or last stopped) SLA clock"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks case.view"),
            @ApiResponse(responseCode = "404", description = "Out of scope, another tenant's, or the case has never had a clock")
    })
    public SlaClockView forCase(@PathVariable UUID id) { return service.forCase(id); }
}
