package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No {@code shipir.encryption-key}: a client secret cannot be stored. Logs in with the seeded admin. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = {"shipir.encryption-key=", "shipir.oidc.allow-insecure-http=true"})
class OidcWithoutEncryptionKeyTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void storingASecretWithoutEncryptionKeyIsRefused() throws Exception {
        jdbc.update("UPDATE users SET password_changed_at = now() WHERE username = 'admin'");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'h', 'LOCAL', id FROM users WHERE username = 'admin'");
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"test-initial-admin-password\"}")).andReturn();
        MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);

        mvc.perform(put("/api/settings/oidc").session(session).cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                        .contentType(APPLICATION_JSON)
                        .content("{\"issuerUrl\":\"https://idp.example.com\",\"clientId\":\"c\",\"clientSecret\":\"s\","
                                + "\"scopes\":\"openid\",\"roleMapping\":{},\"autoProvision\":false,"
                                + "\"localLoginEnabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCRYPTION_KEY_MISSING"));
    }
}
