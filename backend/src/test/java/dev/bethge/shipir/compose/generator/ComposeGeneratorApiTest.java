package dev.bethge.shipir.compose.generator;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ComposeGeneratorApiTest {

    private static final String PASSWORD = "A-test-Passw0rd-for-compose!";
    private static final String VALID = """
            {"spec":{"services":[{"name":"web","image":"nginx","ports":[{"host":8080,"container":80,"protocol":"tcp"}]}]}}""";
    private static final String BAD_PORT = """
            {"spec":{"services":[{"name":"web","image":"nginx","ports":[{"container":0}]}]}}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void seedUsers() {
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
    }

    private MockHttpServletRequestBuilder generate(String username, String body) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MockHttpServletRequestBuilder req = post("/api/compose/generate").contentType(APPLICATION_JSON).content(body);
        if (username == null) {
            return req.cookie(anon).header("X-XSRF-TOKEN", anon.getValue());
        }
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return req.session((MockHttpSession) r.getRequest().getSession(false))
                .cookie(anon).header("X-XSRF-TOKEN", anon.getValue());
    }

    private MockHttpSession sessionOf(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void operatorGetsYaml() throws Exception {
        mvc.perform(generate("operator", VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yaml").value("services:\n  web:\n    image: nginx\n    ports:\n      - 8080:80/tcp\n"));
    }

    @Test
    void invalidSpecIs400() throws Exception {
        mvc.perform(generate("operator", BAD_PORT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPOSE_SPEC"))
                .andExpect(jsonPath("$.details[0]").value("services.web.ports: container port must be 1-65535"));
    }

    @Test
    void viewerForbidden() throws Exception {
        mvc.perform(generate("viewer", VALID)).andExpect(status().isForbidden());
    }

    @Test
    void anonymousUnauthorized() throws Exception {
        mvc.perform(generate(null, VALID)).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceWithDomainGetsProxyLabels() throws Exception {
        String body = """
                {"spec":{"services":[{"name":"web","image":"nginx",
                  "domain":{"host":"blog.example.com","port":80,"proxy":"CADDY","network":null}}]}}""";
        mvc.perform(generate("operator", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yaml").value(org.hamcrest.Matchers.containsString("caddy: blog.example.com")));
    }

    private static final String CADDY_DOMAIN = """
            {"hostId":"%s","spec":{"services":[{"name":"web","image":"nginx",
              "domain":{"host":"blog.example.com","port":80,"proxy":"CADDY","network":null}}]}}""";

    /** A host owned by admin; with a managed proxy of the given type unless null. */
    private java.util.UUID host(String proxyType) {
        java.util.UUID id = jdbc.queryForObject("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Generator host', 'LOCAL', id FROM users WHERE username = 'admin' RETURNING id",
                java.util.UUID.class);
        if (proxyType != null) {
            jdbc.update("INSERT INTO proxy_instances (host_id, type, acme_email, default_cert_mode) "
                    + "VALUES (?, ?, 'ops@example.com', 'ACME_HTTP')", id, proxyType);
        }
        return id;
    }

    @org.junit.jupiter.api.AfterEach
    void removeHosts() {
        jdbc.update("DELETE FROM proxy_instances");
        jdbc.update("DELETE FROM permissions");
        jdbc.update("DELETE FROM docker_hosts WHERE name = 'Generator host'");
    }

    @Test
    void invisibleHostIsIndistinguishableFromAnUnknownOne() throws Exception {
        java.util.UUID hidden = host("CADDY");
        String unknown = mvc.perform(generate("operator", CADDY_DOMAIN.formatted(java.util.UUID.randomUUID())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        mvc.perform(generate("operator", CADDY_DOMAIN.formatted(hidden)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(unknown));
    }

    @Test
    void visibleHostWithManagedCaddyRejectsCaddyLabels() throws Exception {
        java.util.UUID id = host("CADDY");
        grantView(id);
        mvc.perform(generate("operator", CADDY_DOMAIN.formatted(id)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPOSE_SPEC"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("managed Caddy")));
    }

    @Test
    void visibleHostWithManagedTraefikOrNoProxyKeepsCaddyLabels() throws Exception {
        for (String type : new String[] {"TRAEFIK", null}) {
            java.util.UUID id = host(type);
            grantView(id);
            mvc.perform(generate("operator", CADDY_DOMAIN.formatted(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.yaml").value(org.hamcrest.Matchers.containsString("caddy: blog.example.com")));
            removeHosts();
        }
    }

    private void grantView(java.util.UUID hostId) {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, 'VIEW' FROM users "
                + "WHERE username = 'operator'", hostId);
    }

    @Test
    void unknownHostIdIs404InsteadOfSilentlyIgnored() throws Exception {
        String body = """
                {"hostId":"00000000-0000-0000-0000-000000000001","spec":{"services":[{"name":"web","image":"nginx"}]}}""";
        mvc.perform(generate("operator", body)).andExpect(status().isNotFound());
    }

    @Test
    void backtickPayloadInDomainHostIs400WithViolation() throws Exception {
        String body = """
                {"spec":{"services":[{"name":"web","image":"nginx",
                  "domain":{"host":"x.example.com`) || Host(`y","port":80,"proxy":"TRAEFIK","network":null}}]}}""";
        mvc.perform(generate("operator", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPOSE_SPEC"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("domain.host")));
    }

    @Test
    void everyRoleCanReadTheTraefikTemplate() throws Exception {
        for (String user : new String[] {"viewer", "operator"}) {
            mvc.perform(get("/api/compose/proxy-templates/traefik").session(sessionOf(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.yaml").value(org.hamcrest.Matchers.containsString("image: traefik:v3.1")));
        }
    }

    @Test
    void unknownProxyTemplateIs404AndAnonymousIs401() throws Exception {
        mvc.perform(get("/api/compose/proxy-templates/caddy").session(sessionOf("operator")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROXY_TEMPLATE_NOT_FOUND"));
        mvc.perform(get("/api/compose/proxy-templates/traefik")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingSpecIs400() throws Exception {
        mvc.perform(generate("operator", "{}")).andExpect(status().isBadRequest());
    }

    /** Every key the wizard's buildSpec (frontend/src/features/deployments/wizard/model.ts) sends must be accepted. */
    @Test
    void fullWizardPayloadWithNullOptionalsIsAccepted() throws Exception {
        String body = """
                {"spec":{
                  "services":[{"name":"web","image":"nginx","command":null,"restart":null,
                    "ports":[{"host":null,"container":80,"protocol":"tcp"}],
                    "environment":[{"key":"A","value":"x","ref":null},{"key":"B","value":null,"ref":"DB_PASS"}],
                    "volumes":[{"source":"data","target":"/data","readOnly":false}],
                    "networks":["front"],"labels":null,"dependsOn":[]}],
                  "networks":[{"name":"front","driver":null,"external":false}],
                  "volumes":[{"name":"data","driver":null,"external":false}]}}""";
        mvc.perform(generate("operator", body)).andExpect(status().isOk());
    }

    @Test
    void unknownTopLevelFieldIs400() throws Exception {
        mvc.perform(generate("operator", VALID.replace("}}", "}, \"volums\": []}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void misspelledServiceFieldIs400() throws Exception {
        String body = """
                {"spec":{"services":[{"name":"web","imag":"nginx"}]}}""";
        mvc.perform(generate("operator", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unknownFieldInNestedWizardObjectIs400() throws Exception {
        String body = """
                {"spec":{"services":[{"name":"web","image":"nginx","ports":[{"host":8080,"container":80,"protoco":"tcp"}]}]}}""";
        mvc.perform(generate("operator", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
