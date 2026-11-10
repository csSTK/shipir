package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A stored client secret is never carried over to a different issuer: it has to be entered again. */
class OidcSecretEndpointChangeTest extends OidcTestBase {

    private byte[] ciphertext() {
        return jdbc.queryForObject("SELECT client_secret_ciphertext FROM oidc_settings", byte[].class);
    }

    @Test
    void changedIssuerWithoutNewSecretIsRejectedAndNothingChanges() throws Exception {
        configure(false, true);
        byte[] before = ciphertext();
        Login admin = login("admin");
        mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson("https://attacker.example.com", "", false, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SECRET_REQUIRED_FOR_NEW_ENDPOINT"));
        assertThat(ciphertext()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT issuer_url FROM oidc_settings", String.class)).isEqualTo(provider.issuer());
    }

    @Test
    void changedIssuerWithNewSecretWorksAndAuditCarriesBothIssuers() throws Exception {
        configure(false, true);
        byte[] before = ciphertext();
        Login admin = login("admin");
        mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson("https://other.example.com", ",\"clientSecret\":\"brand-new-secret\"", false, true)))
                .andExpect(status().isOk());
        assertThat(ciphertext()).isNotEqualTo(before);
        String details = jdbc.queryForObject("SELECT details::text FROM audit_log WHERE action = 'OIDC_SETTINGS_UPDATE' "
                + "AND outcome = 'SUCCESS' ORDER BY occurred_at DESC LIMIT 1", String.class);
        assertThat(details).contains(provider.issuer()).contains("https://other.example.com")
                .doesNotContain("brand-new-secret");
    }

    @Test
    void trailingSlashAndHostCaseAreNotAChange() throws Exception {
        configure(false, true);
        Login admin = login("admin");
        mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson(provider.issuer() + "/", "", false, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecretConfigured").value(true));
    }

    @Test
    void scopeOnlyEditNeedsNoSecret() throws Exception {
        configure(false, true);
        Login admin = login("admin");
        mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson(provider.issuer(), "", true, true).replace("openid email profile groups", "openid email")))
                .andExpect(status().isOk());
    }

    @Test
    void firstConfigurationWithoutStoredSecretNeedsNoReentry() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson(provider.issuer(), "", false, true)))
                .andExpect(status().isOk());
    }
}
