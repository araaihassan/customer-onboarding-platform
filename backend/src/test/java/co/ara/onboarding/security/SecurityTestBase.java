package co.ara.onboarding.security;

import co.ara.onboarding.auth.TokenService;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The nine negative security tests (spec 11.3, 12; DelegationGuardTest added by
 * sub-project 2 Task 4).
 *
 * Every one of them goes through MockMvc against real HTTP endpoints rather than
 * calling services. That is the whole point: a service-level test cannot catch a
 * missing security rule, a filter ordering mistake, or a controller that forgets to
 * delegate — which are exactly the bypasses these tests exist to find.
 */
@AutoConfigureMockMvc
abstract class SecurityTestBase extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;

    /** Attaches a real access token for the given user. */
    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, AppUser user) {
        return request.header("Authorization", "Bearer " + tokens.issueAccessToken(user));
    }
}
