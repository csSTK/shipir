package dev.bethge.shipir.ai.settings;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real Postgres, one user per role, encryption key configured, ADMIN has an environment (setup gate). */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
abstract class AiSettingsTestBase {

    static final String PASSWORD = "A-test-Passw0rd-for-ai-settings!";
    static final String KEY = "nvapi-SECRETKEY1234567890abcdef";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("UPDATE ai_settings SET active_provider = 'OPENAI_COMPATIBLE', enabled = FALSE");
        jdbc.update("UPDATE ai_provider_configs SET base_url = 'https://integrate.api.nvidia.com/v1', "
                + "model = 'z-ai/glm-5.3-flash', api_key_ciphertext = NULL, api_key_hint = NULL "
                + "WHERE provider = 'OPENAI_COMPATIBLE'");
        jdbc.update("UPDATE ai_provider_configs SET base_url = 'https://generativelanguage.googleapis.com/v1beta', "
                + "model = 'gemini-flash-latest', api_key_ciphertext = NULL, api_key_hint = NULL "
                + "WHERE provider = 'GEMINI'");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'AI host', 'LOCAL', id FROM users WHERE username = 'admin'");
    }

    record Login(MockHttpSession session, Cookie csrf) {

        MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
            return b.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login")
                .cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    /** Edits the OPENAI_COMPATIBLE config with it as the active provider. */
    static String json(String baseUrl, String model, boolean enabled, String apiKey) {
        return json("OPENAI_COMPATIBLE", "OPENAI_COMPATIBLE", baseUrl, model, enabled, apiKey);
    }

    static String json(String activeProvider, String provider, String baseUrl, String model, boolean enabled,
                       String apiKey) {
        String keyPart = apiKey == null ? "" : ",\"apiKey\":\"%s\"".formatted(apiKey);
        return "{\"enabled\":%s,\"activeProvider\":\"%s\",\"provider\":\"%s\",\"baseUrl\":\"%s\",\"model\":\"%s\"%s}"
                .formatted(enabled, activeProvider, provider, baseUrl, model, keyPart);
    }
}
