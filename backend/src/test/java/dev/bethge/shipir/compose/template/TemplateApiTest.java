package dev.bethge.shipir.compose.template;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class TemplateApiTest {

    private static final String PASSWORD = "A-test-Passw0rd-for-templates!";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    /** Logs in and returns the session plus the anonymous CSRF cookie, as the existing generator API test does. */
    private record Login(MockHttpSession session, Cookie csrf) {
        MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder req) {
            return req.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    private Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    @Test
    void viewerCanListTemplates() throws Exception {
        Login viewer = login("viewer");
        mvc.perform(viewer.apply(get("/api/templates")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(6)))
                .andExpect(jsonPath("$[0].id").isString())
                .andExpect(jsonPath("$[0].spec.services").isArray());
    }

    @Test
    void envHintsExposeDefaultValueAndGenerate() throws Exception {
        Login viewer = login("viewer");
        mvc.perform(viewer.apply(get("/api/templates")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='postgres')].env[0].generate").value(true))
                .andExpect(jsonPath("$[?(@.id=='postgres')].env[0].secret").value(true))
                .andExpect(jsonPath("$[?(@.id=='nextcloud')].env[1].defaultValue").value("localhost"));
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/templates")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyTemplateSpecIsAcceptedByTheWizardEndpoint() throws Exception {
        Login operator = login("operator");
        MvcResult listed = mvc.perform(operator.apply(get("/api/templates"))).andExpect(status().isOk()).andReturn();
        JsonNode templates = JSON.readTree(listed.getResponse().getContentAsString());
        assertThat(templates.size()).isGreaterThanOrEqualTo(6);
        for (JsonNode template : templates) {
            String body = JSON.writeValueAsString(Map.of("spec", template.get("spec")));
            mvc.perform(operator.apply(post("/api/compose/generate").contentType(APPLICATION_JSON).content(body)))
                    .andExpect(status().isOk());
        }
    }
}
