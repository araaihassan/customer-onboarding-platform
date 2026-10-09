package co.ara.onboarding.notification;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/t/{tenantSlug}")
public class NotificationAdminController {

    private final NotificationAdminService service;

    public NotificationAdminController(NotificationAdminService service) { this.service = service; }

    @GetMapping("/admin/notification-templates")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every template, by key"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks notification.manage")})
    public List<TemplateView> templates() { return service.templates(); }

    @PostMapping("/admin/notification-templates")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The template was created"),
            @ApiResponse(responseCode = "400", description = "Malformed key or invalid field"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks notification.manage"),
            @ApiResponse(responseCode = "409", description = "A template with that key exists"),
            @ApiResponse(responseCode = "422", description = "Unknown placeholder, or an unpaired exit subject/body")})
    public TemplateView create(@Valid @RequestBody CreateTemplateRequest request) { return service.createTemplate(request); }

    @PutMapping("/admin/notification-templates/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The template was replaced"),
            @ApiResponse(responseCode = "400", description = "Malformed key or invalid field"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks notification.manage"),
            @ApiResponse(responseCode = "404", description = "No such template"),
            @ApiResponse(responseCode = "422", description = "Key change, unknown placeholder, or an unpaired exit subject/body")})
    public TemplateView update(@PathVariable UUID id, @Valid @RequestBody UpdateTemplateRequest request) {
        return service.updateTemplate(id, request);
    }

    @GetMapping("/admin/notification-policy")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deadline lead times and the automatic-reminder policy"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks notification.manage")})
    public PolicyView policy() { return service.getPolicy(); }

    @PutMapping("/admin/notification-policy")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The policy was replaced"),
            @ApiResponse(responseCode = "400", description = "A kind is missing or the reminder policy is out of range"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks notification.manage"),
            @ApiResponse(responseCode = "422", description = "A lead time outside 1-90, repeated, or more than five for a kind")})
    public PolicyView replacePolicy(@Valid @RequestBody UpdatePolicyRequest request) { return service.replacePolicy(request); }

    @GetMapping("/notification-templates/options")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active templates, for the builder's picker"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "The caller lacks workflow.manage")})
    public List<TemplateOption> options() { return service.templateOptions(); }
}
