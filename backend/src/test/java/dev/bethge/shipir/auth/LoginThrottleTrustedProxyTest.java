package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** With the peer configured as trusted proxy, the forwarded client address is what is throttled. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = "shipir.security.trusted-proxies=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginThrottleTrustedProxyTest {

    @Autowired
    private MockMvc mvc;

    private MvcResult login(Cookie csrf, String name, String forwardedFor) throws Exception {
        return mvc.perform(post("/api/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .header("X-Forwarded-For", forwardedFor).contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"x\"}".formatted(name))).andReturn();
    }

    @Test
    void forwardedAddressIsUsedAndRightmostUntrustedEntryWins() throws Exception {
        Cookie csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        // Client prepends fake values; the proxy appended the real address 198.51.100.7.
        for (int i = 0; i < 20; i++) {
            assertThat(login(csrf, "u" + i, "1.2.3." + i + ", 198.51.100.7").getResponse().getStatus())
                    .isEqualTo(401);
        }

        assertThat(login(csrf, "fresh", "9.9.9.9, 198.51.100.7").getResponse().getStatus()).isEqualTo(429);
        // A different real client behind the same proxy is not affected.
        assertThat(login(csrf, "fresh", "198.51.100.8").getResponse().getStatus()).isEqualTo(401);
    }
}
