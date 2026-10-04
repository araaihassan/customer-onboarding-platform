package co.ara.onboarding.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An audited HTTP request still records the caller's ip and user agent (jobs record neither). */
class HttpAuditContextTest extends SecurityTestBase {
    @Test
    void anHttpRequestRecordsIpAndUserAgent() throws Exception {
        UUID t = fixture.createTenant("audit-http");
        var admin = fixture.createAdminUser(t, "a@audit-http.example");
        mvc.perform(as(post("/api/t/audit-http/customers"), admin)
                        .with(r -> { r.setRemoteAddr("203.0.113.9"); return r; })
                        .header("User-Agent", "audit-test/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"X Ltd\",\"displayName\":\"X\"}"))
                .andExpect(status().is2xxSuccessful());
        Map<String, Object> row = ownerJdbc().queryForMap(
                "select ip, user_agent from audit_event where tenant_id = ? and action = 'customer.created'", t);
        assertThat(String.valueOf(row.get("ip"))).contains("203.0.113.9");
        assertThat(row.get("user_agent")).isEqualTo("audit-test/1.0");
    }
}
