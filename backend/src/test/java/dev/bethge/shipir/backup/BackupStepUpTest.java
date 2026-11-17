package dev.bethge.shipir.backup;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.auth.LoginThrottle;
import dev.bethge.shipir.auth.totp.Totp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Step-up re-authentication (password, TOTP, API token callers) for {@code POST /api/backup}. */
class BackupStepUpTest extends BackupTestBase {

    @Autowired LoginThrottle throttle;

    @BeforeEach
    void clean() {
        ((Map<?, ?>) ReflectionTestUtils.getField(throttle, "entries")).clear();
        jdbc.update("UPDATE users SET totp_secret_ciphertext = NULL, totp_enabled_at = NULL, totp_last_used_step = NULL");
        jdbc.update("UPDATE security_settings SET require_2fa_for_admins = FALSE");
        jdbc.update("DELETE FROM audit_log WHERE action = 'BACKUP_EXPORT'");
        jdbc.update("DELETE FROM api_tokens");
    }

    private static String body(String stepUpJson) {
        return "{\"passphrase\":\"" + PASSPHRASE + "\"" + (stepUpJson == null ? "" : ",\"stepUp\":" + stepUpJson) + "}";
    }

    private int exports() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'BACKUP_EXPORT'", Integer.class);
    }

    @Test
    void missingStepUpIsRefusedAndNothingIsExported() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post("/api/backup")).contentType(APPLICATION_JSON).content(body(null)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));
        mvc.perform(l.write(post("/api/backup")).contentType(APPLICATION_JSON).content(body("{}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));
        assertThat(exports()).isZero();
    }

    @Test
    void wrongPasswordIsRefusedAndTheLockoutCountsAcrossAttempts() throws Exception {
        Login l = login("admin");
        String wrong = body("{\"password\":\"not-the-password\"}");
        for (int i = 0; i < 5; i++) {
            mvc.perform(l.write(post("/api/backup")).contentType(APPLICATION_JSON).content(wrong))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STEP_UP_INVALID"));
        }
        mvc.perform(l.write(post("/api/backup")).contentType(APPLICATION_JSON)
                        .content(body("{\"password\":\"" + PASSWORD + "\"}")))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        assertThat(exports()).isZero();
    }

    @Test
    void correctPasswordExports() throws Exception {
        assertThat(download(login("admin"), PASSPHRASE)).isNotEmpty();
        assertThat(exports()).isEqualTo(1);
    }

    @Test
    void withTwoFactorEnabledThePasswordAloneIsNotEnough() throws Exception {
        Login l = login("admin");
        byte[] secret = enableTwoFactor(l);
        String pw = "\"password\":\"" + PASSWORD + "\"";
        mvc.perform(l.write(post("/api/backup")).contentType(APPLICATION_JSON).content(body("{" + pw + "}")))
                .andExpect(status().isForbidden());
        mvc.perform(l.write(post("/api/backup")).contentType(APPLICATION_JSON)
                        .content(body("{" + pw + ",\"code\":\"000000\"}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STEP_UP_INVALID"));
        assertThat(exports()).isZero();

        String code = Totp.code(secret, Totp.stepAt(Instant.now()) + 1);
        MvcResult ok = mvc.perform(l.write(post("/api/backup")).contentType(APPLICATION_JSON)
                .content(body("{" + pw + ",\"code\":\"" + code + "\"}"))).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anApiTokenCallerCannotBypassTheStepUp() throws Exception {
        Login l = login("admin");
        String created = mvc.perform(l.write(post("/api/account/tokens")).contentType(APPLICATION_JSON)
                .content("{\"name\":\"ci\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.token");
        mvc.perform(post("/api/backup").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body("{\"password\":\"" + PASSWORD + "\"}")))
                .andExpect(status().isForbidden());
        assertThat(exports()).isZero();
    }

    @Test
    void stepUpStatusTellsThePasswordUserWhatToSend() throws Exception {
        Login l = login("admin");
        mvc.perform(get("/api/auth/step-up").session(l.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("PASSWORD"))
                .andExpect(jsonPath("$.totpRequired").value(false))
                .andExpect(jsonPath("$.fresh").value(false));
        enableTwoFactor(l);
        mvc.perform(get("/api/auth/step-up").session(l.session()))
                .andExpect(jsonPath("$.totpRequired").value(true));
    }

    private byte[] enableTwoFactor(Login l) throws Exception {
        String setup = mvc.perform(l.write(post("/api/auth/2fa/setup"))).andReturn().getResponse().getContentAsString();
        byte[] secret = Totp.fromBase32(JsonPath.read(setup, "$.secret"));
        mvc.perform(l.write(post("/api/auth/2fa/confirm")).contentType(APPLICATION_JSON)
                .content("{\"code\":\"" + Totp.code(secret, Totp.stepAt(Instant.now())) + "\"}"))
                .andExpect(status().isOk());
        return secret;
    }
}
