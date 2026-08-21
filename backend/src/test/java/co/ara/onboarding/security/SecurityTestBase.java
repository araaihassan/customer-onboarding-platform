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
public abstract class SecurityTestBase extends PostgresTestBase {

    // public class, protected fields: Task 8 is the first *ApiTest to live in its
    // own domain package (co.ara.onboarding.workflow) rather than alongside the
    // nine negative security tests here, and sub-projects 2-10 add one such module
    // per sub-project (CLAUDE.md), so more of these are coming, not fewer. A
    // package-private base cannot even be named from another package, so widening
    // was not optional once a cross-package subclass existed.
    @Autowired protected MockMvc mvc;
    @Autowired protected TokenService tokens;
    @Autowired protected RoleService roles;
    @Autowired protected TenantFixture fixture;

    /** Attaches a real access token for the given user. */
    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, AppUser user) {
        return request.header("Authorization", "Bearer " + tokens.issueAccessToken(user));
    }
}
