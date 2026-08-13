package co.ara.onboarding.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/t/{tenantSlug}/me")
public class MeController {

    private final MeService me;

    public MeController(MeService me) { this.me = me; }

    /**
     * Returns a typed record rather than a Map, so the generated OpenAPI document
     * describes real fields. A Map<String,Object> would serialise identically and
     * document as an untyped object, which is worse than useless to the frontend
     * generator this task exists to feed.
     */
    @GetMapping
    public MeService.Me me() {
        return me.current();
    }
}
