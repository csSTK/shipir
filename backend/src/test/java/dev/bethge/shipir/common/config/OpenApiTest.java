package dev.bethge.shipir.common.config;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class OpenApiTest {

    private static final String PASSWORD = "A-test-Passw0rd-for-openapi!";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    private MockHttpSession adminSession() throws Exception {
        // a VIEWER has no environment setup step, so the setup gate lets every call through
        User admin = users.findByUsername("openapi-viewer").orElseGet(() -> new User("openapi-viewer", "x", Role.VIEWER));
        admin.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        users.save(admin);
        Cookie csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MockHttpSession session = new MockHttpSession();
        MvcResult login = mvc.perform(post("/api/auth/login").session(session).cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType(APPLICATION_JSON)
                        .content("{\"username\":\"openapi-viewer\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession();
    }

    @Test
    void specRequiresLogin() throws Exception {
        mvc.perform(get("/api/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void specListsControllersAndSecuritySchemes() throws Exception {
        String body = mvc.perform(get("/api/v3/api-docs").session(adminSession()))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"/api/hosts\"", "\"/api/auth/login\"", "\"/api/users\"",
                "\"/api/hosts/{hostId}/managed-deployments\"", "sessionCookie", "X-XSRF-TOKEN");
        assertThat(body).doesNotContain(PASSWORD);
        tools.jackson.databind.JsonNode spec = new tools.jackson.databind.json.JsonMapper().readTree(body);
        assertThat(spec.at("/components/schemas/ErrorResponse/properties/code").isMissingNode()).isFalse();
        assertThat(spec.at("/components/schemas/ErrorResponse/properties/details").isMissingNode()).isFalse();
        for (String status : new String[]{"400", "401", "403", "404"}) {
            assertThat(spec.at("/paths/~1api~1users/get/responses/" + status
                    + "/content/application~1json/schema/$ref").asString())
                    .isEqualTo("#/components/schemas/ErrorResponse");
        }
        // a declared response is not overwritten
        assertThat(spec.at("/paths/~1api~1users/get/responses/200").isMissingNode()).isFalse();
    }

    @Test
    void swaggerUiIsReachableAfterLogin() throws Exception {
        MockHttpSession session = adminSession();
        mvc.perform(get("/api/docs").session(session)).andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/api/swagger-ui/index.html"));
    }
}
