package dev.bethge.shipir.ai.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A stored key is never carried over to a different endpoint: the key has to be entered again. */
@TestPropertySource(properties = "shipir.ai.settings.allow-insecure-http=true")
class AiSettingsEndpointChangeTest extends AiSettingsTestBase {

    private static final String URL = "https://llm.example.com/v1";

    @BeforeEach
    void storeKey() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json(URL, "m", true, KEY))).andExpect(status().isOk());
    }

    private byte[] ciphertext(String provider) {
        return jdbc.queryForObject("SELECT api_key_ciphertext FROM ai_provider_configs WHERE provider = ?",
                byte[].class, provider);
    }

    @Test
    void changedBaseUrlWithoutNewKeyIsRejectedAndNothingChanges() throws Exception {
        byte[] before = ciphertext("OPENAI_COMPATIBLE");
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://attacker.example.com/v1", "m", true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SECRET_REQUIRED_FOR_NEW_ENDPOINT"));
        assertThat(ciphertext("OPENAI_COMPATIBLE")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT base_url FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'",
                String.class)).isEqualTo(URL);
    }

    @Test
    void changedBaseUrlWithNewKeyWorksAndAuditCarriesBothEndpoints() throws Exception {
        byte[] before = ciphertext("OPENAI_COMPATIBLE");
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://other.example.com/v1", "m", true, "nvapi-NEWKEY0987654321zyxwvu")))
                .andExpect(status().isOk());
        assertThat(ciphertext("OPENAI_COMPATIBLE")).isNotEqualTo(before);
        String details = jdbc.queryForObject("SELECT details::text FROM audit_log WHERE action = 'AI_SETTINGS_UPDATE' "
                + "AND outcome = 'SUCCESS' ORDER BY occurred_at DESC LIMIT 1", String.class);
        assertThat(details).contains(URL).contains("https://other.example.com/v1")
                .doesNotContain("NEWKEY").doesNotContain("SECRETKEY");
    }

    @Test
    void modelOnlyEditNeedsNoKey() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json(URL, "other-model", true, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].keyConfigured").value(true));
    }

    @Test
    void trailingSlashAloneIsNotAChange() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json(URL + "/", "m", true, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].keyConfigured").value(true));
    }

    @Test
    void clearingTheKeyWhileChangingTheUrlIsAllowed() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://other.example.com/v1", "m", true, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].keyConfigured").value(false));
    }

    @Test
    void changingTheUrlOfAProviderWithoutKeyNeedsNoKey() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("OPENAI_COMPATIBLE", "GEMINI", "https://gemini.example.com/v1beta", "g", true, null)))
                .andExpect(status().isOk());
    }

    @Test
    void geminiKeyIsProtectedToo() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("OPENAI_COMPATIBLE", "GEMINI", "https://gemini.example.com/v1beta", "g", true, KEY)))
                .andExpect(status().isOk());
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("OPENAI_COMPATIBLE", "GEMINI", "https://evil.example.com/v1beta", "g", true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SECRET_REQUIRED_FOR_NEW_ENDPOINT"));
    }
}
