package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Setup steps: PASSWORD, then (admins only, while no environment exists) ENVIRONMENT, then none. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SetupEnvironmentStepTest {

    private static final String ADMIN_PASSWORD = "test-initial-admin-password";
    private static final String NEW_PASSWORD = "A-brand-new-Passw0rd!";
    private static final String LOCAL = "{\"name\":\"Local\",\"connectionType\":\"LOCAL\"}";

    @DynamicPropertySource
    static void dockerSocket(DynamicPropertyRegistry registry) {
        registry.add("shipir.docker.local-socket",
                () -> "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath());
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private UserRepository users;

    private Cookie csrf;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("UPDATE users SET password_hash = ?, password_changed_at = NULL WHERE username = 'admin'",
                encoder.encode(ADMIN_PASSWORD));
        csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login")
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b, MockHttpSession s) {
        return b.session(s).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    /** Admin session after the password change (rotated session). */
    private MockHttpSession adminAtEnvironmentStep() throws Exception {
        MockHttpSession session = login("admin", ADMIN_PASSWORD);
        MvcResult r = mvc.perform(write(post("/api/auth/password"), session).contentType(APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                        .formatted(ADMIN_PASSWORD, NEW_PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(204);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void freshAdminIsAtPasswordStep() throws Exception {
        MockHttpSession session = login("admin", ADMIN_PASSWORD);

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.setupStep").value("PASSWORD"));
    }

    @Test
    void passwordStepDoesNotAllowHostEndpoints() throws Exception {
        MockHttpSession session = login("admin", ADMIN_PASSWORD);

        mvc.perform(write(post("/api/hosts"), session).contentType(APPLICATION_JSON).content(LOCAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
        mvc.perform(write(post("/api/hosts/connection-test"), session).contentType(APPLICATION_JSON)
                        .content("{\"connectionType\":\"LOCAL\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
    }

    @Test
    void afterPasswordChangeAdminIsAtEnvironmentStep() throws Exception {
        MockHttpSession session = adminAtEnvironmentStep();

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.setupStep").value("ENVIRONMENT"));
    }

    @Test
    void environmentStepAllowsOnlyConnectionTestAndCreateOnHosts() throws Exception {
        MockHttpSession session = adminAtEnvironmentStep();

        mvc.perform(write(post("/api/hosts/connection-test"), session).contentType(APPLICATION_JSON)
                        .content("{\"connectionType\":\"LOCAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get("/api/hosts/overview").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
        mvc.perform(get("/api/hosts").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
        mvc.perform(get("/api/containers").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatingTheFirstHostEndsSetup() throws Exception {
        MockHttpSession session = adminAtEnvironmentStep();

        mvc.perform(write(post("/api/hosts"), session).contentType(APPLICATION_JSON).content(LOCAL))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(jsonPath("$.setupRequired").value(false))
                .andExpect(jsonPath("$.setupStep").doesNotExist());
        mvc.perform(get("/api/hosts/overview").session(session)).andExpect(status().isOk());
    }

    @Test
    void nonAdminWithChangedPasswordAndNoHostsHasNoSetupStep() throws Exception {
        for (Role role : new Role[]{Role.OPERATOR, Role.VIEWER}) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(NEW_PASSWORD), java.time.Instant.now());
            users.save(u);
            MockHttpSession session = login(name, NEW_PASSWORD);

            mvc.perform(get("/api/auth/me").session(session))
                    .andExpect(jsonPath("$.setupRequired").value(false))
                    .andExpect(jsonPath("$.setupStep").doesNotExist());
            mvc.perform(get("/api/hosts/overview").session(session)).andExpect(status().isOk());
        }
    }
}
