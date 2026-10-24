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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shipir.openapi.enabled=false")
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class OpenApiDisabledTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ApplicationContext context;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @Test
    void disabledPropertyRemovesDocs() throws Exception {
        assertThat(context.getBeansOfType(io.swagger.v3.oas.models.OpenAPI.class)).isEmpty();
        mvc.perform(get("/api/v3/api-docs")).andExpect(status().isUnauthorized());

        User user = users.findByUsername("openapi-viewer").orElseGet(() -> new User("openapi-viewer", "x", Role.VIEWER));
        user.changePasswordHash(encoder.encode("A-test-Passw0rd-for-openapi!"), Instant.now());
        users.save(user);
        Cookie csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MockHttpSession session = new MockHttpSession();
        MvcResult login = mvc.perform(post("/api/auth/login").session(session).cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType(APPLICATION_JSON)
                        .content("{\"username\":\"openapi-viewer\",\"password\":\"A-test-Passw0rd-for-openapi!\"}"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession authed = (MockHttpSession) login.getRequest().getSession();
        mvc.perform(get("/api/v3/api-docs").session(authed)).andExpect(status().isNotFound());
        mvc.perform(get("/api/docs").session(authed)).andExpect(status().isNotFound());
    }
}
