package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default egress policy (private ranges denied): "Test connection" answers a denied issuer like an unreachable one. */
@TestPropertySource(properties = "shipir.egress.allow-private-ranges=false")
class OidcSettingsDeniedIssuerTest extends OidcTestBase {

    @Test
    void testEndpointReportsUnreachableForDeniedIssuerAndStubSeesNoRequest() throws Exception {
        // the stub issuer is on loopback, which the default policy denies; saving does not fetch it
        configure(true, true);
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/settings/oidc/test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNREACHABLE"));
        org.assertj.core.api.Assertions.assertThat(provider.discoveryHits()).isZero();
    }
}
