package co.ara.onboarding.journey;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The review screen's own two calls: preview a target version, then migrate the chosen cases. */
@RestController
@RequestMapping("/api/t/{tenantSlug}/cases/migration")
public class MigrationController {

    private static final String FORBIDDEN = "Caller holds no sufficient case.migrate grant";

    private final MigrationService migrations;

    public MigrationController(MigrationService migrations) { this.migrations = migrations; }

    @GetMapping
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "How many cases sit on an older version, and which are eligible with why not"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The target version is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public MigrationPreviewView preview(@RequestParam UUID versionId) {
        return migrations.preview(versionId);
    }

    @PostMapping
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every requested case migrated -- refused rather than partially applied"),
            @ApiResponse(responseCode = "400", description = "An empty case list failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The target version or a case is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "A requested case is not eligible -- refused, not silently skipped",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public MigrateResultView migrate(@Valid @RequestBody MigrateRequest request) {
        return new MigrateResultView(migrations.migrate(request.versionId(), request.caseIds()));
    }
}
