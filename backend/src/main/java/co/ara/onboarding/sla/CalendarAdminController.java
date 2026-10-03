package co.ara.onboarding.sla;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/t/{tenantSlug}/admin")
public class CalendarAdminController {

    private final CalendarAdminService service;

    public CalendarAdminController(CalendarAdminService service) { this.service = service; }

    @GetMapping("/business-calendar")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The calendar and its holidays"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks calendar.manage")})
    public BusinessCalendarView calendar() { return service.calendar(); }

    @PutMapping("/business-calendar")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The calendar was replaced (holidays untouched)"),
            @ApiResponse(responseCode = "400", description = "Unknown timezone, or working days outside 1-7"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks calendar.manage")})
    public BusinessCalendarView updateCalendar(@Valid @RequestBody UpdateBusinessCalendarRequest request) {
        return service.updateCalendar(request);
    }

    @PostMapping("/business-calendar/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The holiday was added"),
            @ApiResponse(responseCode = "400", description = "Invalid date or name"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks calendar.manage"),
            @ApiResponse(responseCode = "409", description = "A holiday already exists on that date")})
    public HolidayView addHoliday(@Valid @RequestBody CreateHolidayRequest request) { return service.addHoliday(request); }

    /**
     * POST .../remove, following the codebase's convention; the row is genuinely deleted --
     * a holiday is configuration (V28).
     */
    @PostMapping("/business-calendar/holidays/{id}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The holiday was removed"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks calendar.manage"),
            @ApiResponse(responseCode = "404", description = "No such holiday in this tenant")})
    public void removeHoliday(@PathVariable UUID id) { service.removeHoliday(id); }

    @GetMapping("/sla-policy")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tenant's SLA policy"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks calendar.manage")})
    public SlaPolicyView policy() { return service.policy(); }

    @PutMapping("/sla-policy")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The policy was replaced"),
            @ApiResponse(responseCode = "400", description = "atRiskDays negative or off the 0.1 grid, or escalateAfterOverdueDays below 1"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks calendar.manage")})
    public SlaPolicyView updatePolicy(@Valid @RequestBody UpdateSlaPolicyRequest request) { return service.updatePolicy(request); }
}
