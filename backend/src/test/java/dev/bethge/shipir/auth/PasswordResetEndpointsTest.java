package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.dto.PasswordResetConfirmRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, PasswordResetEndpointsTest.TestBeans.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetEndpointsTest {

    private static final String OLD_PASSWORD = "Old-Passw0rd-for-tests!";
    private static final String NEW_PASSWORD = "A-brand-new-Passw0rd!";

    static class CapturingSender implements PasswordResetCodeSender {
        final List<String> codes = new ArrayList<>();

        @Override
        public void send(User user, String code) {
            codes.add(code);
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CapturingSender sender;

    private String username;
    private Cookie csrf;

    @BeforeEach
    void setUp() throws Exception {
        username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new User(username, encoder.encode(OLD_PASSWORD), Role.OPERATOR));
        sender.codes.clear();
        csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private MvcResult send(String path, String json) throws Exception {
        return mvc.perform(post(path).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON).content(json)).andReturn();
    }

    private int requestReset(String name) throws Exception {
        return send("/api/auth/password-reset/request", "{\"username\":\"%s\"}".formatted(name))
                .getResponse().getStatus();
    }

    private MvcResult confirm(String name, String code, String pw) throws Exception {
        return send("/api/auth/password-reset/confirm",
                "{\"username\":\"%s\",\"code\":\"%s\",\"newPassword\":\"%s\"}".formatted(name, code, pw));
    }

    private int login(String pw) throws Exception {
        return send("/api/auth/login", "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, pw))
                .getResponse().getStatus();
    }

    private String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    @Test
    void requestReturnsIdenticalEmptyAcceptedForExistingAndUnknownUser() throws Exception {
        MvcResult known = send("/api/auth/password-reset/request", "{\"username\":\"%s\"}".formatted(username));
        MvcResult unknown = send("/api/auth/password-reset/request", "{\"username\":\"nobody-xyz\"}");

        assertThat(known.getResponse().getStatus()).isEqualTo(202);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(202);
        assertThat(known.getResponse().getContentAsString()).isEmpty();
        assertThat(unknown.getResponse().getContentAsString()).isEmpty();
        assertThat(sender.codes).hasSize(1);
    }

    @Test
    void validCodeResetsPasswordWithoutLoggingIn() throws Exception {
        requestReset(username);
        MvcResult result = confirm(username, sender.codes.get(0), NEW_PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(login(NEW_PASSWORD)).isEqualTo(200);
        assertThat(login(OLD_PASSWORD)).isEqualTo(401);
    }

    @Test
    void validCodeRevokesTheUsersApiTokensButAWrongCodeDoesNot() throws Exception {
        insertToken();
        requestReset(username);
        confirm(username, wrong(sender.codes.get(0)), NEW_PASSWORD);
        assertThat(tokenCount()).isEqualTo(1);

        assertThat(confirm(username, sender.codes.get(0), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);

        assertThat(tokenCount()).isZero();
    }

    private void insertToken() {
        String plain = dev.bethge.shipir.auth.token.TokenCodec.newToken("shp_");
        jdbc.update("INSERT INTO api_tokens (user_id, name, token_hash, prefix, created_at, expires_at) "
                        + "SELECT id, 'seeded', ?, ?, now(), now() + interval '30 days' FROM users WHERE username = ?",
                dev.bethge.shipir.auth.token.TokenCodec.sha256Hex(plain), plain.substring(0, 8), username);
    }

    private int tokenCount() {
        return jdbc.queryForObject("SELECT count(*) FROM api_tokens t JOIN users u ON u.id = t.user_id "
                + "WHERE u.username = ?", Integer.class, username);
    }

    @Test
    void wrongCodeIsInvalidResetCode() throws Exception {
        requestReset(username);
        MvcResult r = confirm(username, wrong(sender.codes.get(0)), NEW_PASSWORD);

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("\"code\":\"INVALID_RESET_CODE\"");
    }

    @Test
    void usedCodeIsInvalidResetCode() throws Exception {
        requestReset(username);
        String code = sender.codes.get(0);
        confirm(username, code, NEW_PASSWORD);
        MvcResult r = confirm(username, code, "Another-Passw0rd-123!");

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("INVALID_RESET_CODE");
    }

    @Test
    void expiredCodeIsInvalidResetCode() throws Exception {
        requestReset(username);
        jdbc.update("UPDATE password_reset_codes SET expires_at = now() - interval '1 minute'");
        MvcResult r = confirm(username, sender.codes.get(0), NEW_PASSWORD);

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("INVALID_RESET_CODE");
    }

    @Test
    void unknownUserConfirmIsInvalidResetCode() throws Exception {
        MvcResult r = confirm("nobody-xyz", "123456", NEW_PASSWORD);

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("INVALID_RESET_CODE");
    }

    @Test
    void policyViolationReturnsPasswordPolicyWithViolations() throws Exception {
        requestReset(username);
        MvcResult r = confirm(username, sender.codes.get(0), "short");

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString())
                .contains("PASSWORD_POLICY").contains("TOO_SHORT");
    }

    @Test
    void missingCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/api/auth/password-reset/request").contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\"}".formatted(username))).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/password-reset/confirm").contentType(APPLICATION_JSON)
                .content("{\"username\":\"a\",\"code\":\"123456\",\"newPassword\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void blankFieldsAreBadRequest() throws Exception {
        assertThat(send("/api/auth/password-reset/request", "{\"username\":\" \"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(confirm(username, "", NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(confirm("", "123456", NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(confirm(username, "123456", "").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void confirmRequestToStringHidesCodeAndPassword() {
        String text = new PasswordResetConfirmRequest("alice", "123456", NEW_PASSWORD).toString();

        assertThat(text).doesNotContain("123456").doesNotContain(NEW_PASSWORD);
    }
}
