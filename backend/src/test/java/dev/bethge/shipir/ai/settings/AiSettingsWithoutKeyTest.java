package dev.bethge.shipir.ai.settings;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "shipir.encryption-key=")
class AiSettingsWithoutKeyTest extends AiSettingsTestBase {

    @Autowired
    AiSettingsService service;

    @Test
    void settingAKeyWithoutEncryptionKeyIsRejected() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                        .content(json("https://llm.example.com/v1", "m", true, KEY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCRYPTION_KEY_MISSING"));
        assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'", byte[].class)).isNull();
        assertThat(service.activeConfig()).isEmpty();
    }

    @Test
    void settingsWithoutKeyStillReadableAndEditable() throws Exception {
        Login l = login("admin");
        mvc.perform(get("/api/settings/ai").session(l.session())).andExpect(status().isOk());
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                        .content(json("https://llm.example.com/v1", "other", false, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].model").value("other"));
    }
}
