package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OidcSettingsApiTest extends OidcTestBase {

    private static final String SECRET_PART = ",\"clientSecret\":\"" + StubOidcProvider.CLIENT_SECRET + "\"";

    @Test
    void getReturnsEmptyDefaults() throws Exception {
        Login admin = login("admin");
        mvc.perform(get("/api/settings/oidc").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.clientSecretConfigured").value(false))
                .andExpect(jsonPath("$.localLoginEnabled").value(true))
                .andExpect(jsonPath("$.scopes").value("openid email profile"));
    }

    @Test
    void onlyAdminMayReadOrWrite() throws Exception {
        for (String name : new String[]{"operator", "viewer"}) {
            Login l = login(name);
            mvc.perform(get("/api/settings/oidc").session(l.session())).andExpect(status().isForbidden());
            mvc.perform(l.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                    .content(settingsJson(provider.issuer(), SECRET_PART, false, true))).andExpect(status().isForbidden());
            mvc.perform(l.write(post("/api/settings/oidc/test"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/settings/oidc")).andExpect(status().isUnauthorized());
    }

    @Test
    void putRequiresCsrfToken() throws Exception {
        Login admin = login("admin");
        mvc.perform(put("/api/settings/oidc").session(admin.session()).contentType(APPLICATION_JSON)
                .content(settingsJson(provider.issuer(), SECRET_PART, false, true))).andExpect(status().isForbidden());
    }

    @Test
    void putStoresSecretEncryptedAndNeverReturnsIt() throws Exception {
        Login admin = login("admin");
        MvcResult r = mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson(provider.issuer(), SECRET_PART, true, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.clientSecretConfigured").value(true))
                .andExpect(jsonPath("$.roleMapping.ADMIN[0]").value("admins"))
                .andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).doesNotContain(StubOidcProvider.CLIENT_SECRET).contains("clientSecretHint");

        String again = mvc.perform(get("/api/settings/oidc").session(admin.session())).andReturn()
                .getResponse().getContentAsString();
        assertThat(again).doesNotContain(StubOidcProvider.CLIENT_SECRET);
        byte[] stored = jdbc.queryForObject("SELECT client_secret_ciphertext FROM oidc_settings", byte[].class);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(StubOidcProvider.CLIENT_SECRET);
    }

    @Test
    void omittedSecretKeepsStoredOneAndEmptyClearsIt() throws Exception {
        configure(false, true);
        Login admin = login("admin");
        mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson(provider.issuer(), "", false, true)))
                .andExpect(jsonPath("$.clientSecretConfigured").value(true));
        mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson(provider.issuer(), ",\"clientSecret\":\"\"", false, true)))
                .andExpect(jsonPath("$.clientSecretConfigured").value(false))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void updateIsAuditedWithoutSecret() throws Exception {
        configure(true, true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'OIDC_SETTINGS_UPDATE' "
                + "AND outcome = 'SUCCESS' AND username = 'admin'", Integer.class)).isEqualTo(1);
        String all = String.join("|", jdbc.queryForList(
                "SELECT coalesce(details::text,'') || coalesce(target_id,'') FROM audit_log", String.class));
        assertThat(all).doesNotContain(StubOidcProvider.CLIENT_SECRET);
    }

    @Test
    void invalidRoleMappingIsRejected() throws Exception {
        Login admin = login("admin");
        for (String mapping : new String[]{"{\"SUPERUSER\":[\"x\"]}", "{\"ADMIN\":\"x\"}", "{\"ADMIN\":[\"\"]}",
                "{\"ADMIN\":[1]}", "[]"}) {
            mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                    .content(settingsJson(provider.issuer(), SECRET_PART, true, true)
                            .replace(ROLE_MAPPING, mapping)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ROLE_MAPPING"));
        }
    }

    @Test
    void scopesMustContainOpenid() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson(provider.issuer(), SECRET_PART, true, true)
                        .replace("openid email profile groups", "email profile")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCOPES"));
    }

    @Test
    void issuerUrlMustBeHttpsWithoutCredentials() throws Exception {
        Login admin = login("admin");
        for (String issuer : new String[]{"ftp://idp.example.com", "https://user:pw@idp.example.com", "not a url",
                "https://idp.example.com/?x=1"}) {
            mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                    .content(settingsJson(issuer, SECRET_PART, true, true)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ISSUER_URL"));
        }
    }

    @Test
    void localLoginCannotBeDisabledWithoutWorkingOidc() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson(provider.issuer(), "", false, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCAL_LOGIN_REQUIRED"));
    }

    @Test
    void testEndpointReportsOkAndUnreachable() throws Exception {
        configure(true, true);
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/settings/oidc/test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK"));
        provider.breakDiscovery();
        mvc.perform(admin.write(post("/api/settings/oidc/test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNREACHABLE"));
    }

    @Test
    void testEndpointWithoutConfigurationIsBadRequest() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/settings/oidc/test")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OIDC_NOT_CONFIGURED"));
    }
}
