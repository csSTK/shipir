package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * {@code OidcLoginService} caches the ClientRegistration and the ID token decoder. A client secret or client id
 * saved through the settings API must be used by the very next sign-in, not the cached credentials.
 */
class OidcCredentialRotationTest extends OidcTestBase {

    private void save(String clientId, String clientSecret) throws Exception {
        String body = ("{\"issuerUrl\":\"%s\",\"clientId\":\"%s\",\"clientSecret\":\"%s\",\"scopes\":\"openid email profile groups\","
                + "\"groupsClaim\":\"groups\",\"roleMapping\":%s,\"autoProvision\":true,\"localLoginEnabled\":true}")
                .formatted(provider.issuer(), clientId, clientSecret, ROLE_MAPPING);
        var r = mvc.perform(login("admin").write(put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(body)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    @Test
    void rotatedClientSecretIsUsedOnNextSignIn() throws Exception {
        configure(true, true);
        assertThat(signIn(claims("sub-a", "alice", "ops")).callbackLocation()).isEqualTo("/");

        provider.expectClient(StubOidcProvider.CLIENT_ID, "rotated-secret-VALUE-67890");
        save(StubOidcProvider.CLIENT_ID, "rotated-secret-VALUE-67890");

        assertThat(signIn(claims("sub-b", "bob", "ops")).callbackLocation()).isEqualTo("/");
    }

    @Test
    void changedClientIdIsUsedOnNextSignIn() throws Exception {
        configure(true, true);
        assertThat(signIn(claims("sub-a", "alice", "ops")).callbackLocation()).isEqualTo("/");

        provider.expectClient("renamed-client", "renamed-secret-VALUE-13579");
        save("renamed-client", "renamed-secret-VALUE-13579");

        Flow f = signIn(claims("sub-b", "bob", "ops"));
        assertThat(f.authorizeQuery()).containsEntry("client_id", "renamed-client");
        assertThat(f.callbackLocation()).isEqualTo("/");
    }
}
