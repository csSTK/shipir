package dev.bethge.shipir.common.config;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default (plain HTTP) setup: security headers on API answers, no HSTS, XSRF cookie without Secure. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class SecurityHeadersTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void apiAnswersCarrySecurityHeadersEvenOn401() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("Permissions-Policy"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"))
                .andReturn();

        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("default-src 'self'", "frame-ancestors 'none'", "object-src 'none'",
                "connect-src 'self' ws: wss:", "worker-src 'self' blob:");
        assertThat(csp).doesNotContain("unsafe-eval").doesNotContain("script-src 'self' 'unsafe-inline'");
        assertThat(result.getResponse().getHeader("Permissions-Policy")).contains("camera=()");
    }

    @Test
    void xsrfCookieIsNotSecureOverPlainHttp() throws Exception {
        assertThat(mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN").getSecure())
                .isFalse();
    }
}
