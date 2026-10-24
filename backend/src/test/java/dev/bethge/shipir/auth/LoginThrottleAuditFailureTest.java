package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.audit.AuditService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class LoginThrottleAuditFailureTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AuditService auditService;

    private int send(Cookie csrf, String path, String json) throws Exception {
        return mvc.perform(post(path).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON).content(json)).andReturn().getResponse().getStatus();
    }

    @Test
    void failingAuditDoesNotChangeTheLockoutStartingLoginResponse() throws Exception {
        doThrow(new IllegalStateException("audit down")).when(auditService).record(any());
        Cookie csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        String body = "{\"username\":\"audit-fail-user\",\"password\":\"wrong\"}";

        for (int i = 0; i < 5; i++) {
            assertThat(send(csrf, "/api/auth/login", body)).isEqualTo(401);
        }
        assertThat(send(csrf, "/api/auth/login", body)).isEqualTo(429);
    }

    @Test
    void failingAuditDoesNotChangeTheLockoutStartingResetRequestResponse() throws Exception {
        doThrow(new IllegalStateException("audit down")).when(auditService).record(any());
        Cookie csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        String body = "{\"username\":\"audit-fail-reset\"}";

        for (int i = 0; i < 5; i++) {
            assertThat(send(csrf, "/api/auth/password-reset/request", body)).isEqualTo(202);
        }
        assertThat(send(csrf, "/api/auth/password-reset/request", body)).isEqualTo(429);
    }
}
