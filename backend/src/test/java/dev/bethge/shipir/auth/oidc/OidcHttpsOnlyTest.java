package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "shipir.oidc.allow-insecure-http=false")
class OidcHttpsOnlyTest extends OidcTestBase {

    @Test
    void plainHttpIssuerIsRejectedByDefault() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson("http://127.0.0.1:1", ",\"clientSecret\":\"s\"", true, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ISSUER_URL"));
    }
}
