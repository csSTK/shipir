package dev.bethge.shipir.ai.settings;

import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default properties: plain http is refused. */
class AiSettingsHttpsOnlyTest extends AiSettingsTestBase {

    @Test
    void httpBaseUrlIsRejectedByDefault() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                        .content(json("http://llm.example.com/v1", "m", true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BASE_URL"));
    }
}
