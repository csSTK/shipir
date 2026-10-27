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
