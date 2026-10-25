package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** An unexpected failure while evaluating a reset code must keep the provisional count (fail closed). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginThrottleFailClosedTest {

    @Autowired
    private MockMvc mvc;
    @MockitoSpyBean
    private PasswordResetService resetService;

    @Test
    void unexpectedFailuresStillCountAgainstTheLimit() throws Exception {
        doThrow(new IllegalStateException("db down")).when(resetService).confirmReset(any(), any(), any());
        Cookie csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        String body = "{\"username\":\"nobody-failclosed\",\"code\":\"123456\",\"newPassword\":\"A-brand-new-Passw0rd!\"}";

        for (int i = 0; i < 5; i++) {
            // MockMvc rethrows the unhandled exception instead of answering 500.
            assertThatThrownBy(() -> mvc.perform(post("/api/auth/password-reset/confirm").cookie(csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue()).contentType(APPLICATION_JSON).content(body)))
                    .hasRootCauseMessage("db down");
        }

        assertThat(mvc.perform(post("/api/auth/password-reset/confirm").cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType(APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus()).isEqualTo(429);
    }
}
