package dev.bethge.shipir.ai.settings;

import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.ai.settings.dto.AiSettingsRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"shipir.ai.settings.allow-insecure-http=true",
        "shipir.ai.settings.test-timeout=1s"})
class AiSettingsApiTest extends AiSettingsTestBase {

    @Autowired
    AiSettingsService service;

    private HttpServer server;
    private final AtomicInteger modelsStatus = new AtomicInteger(200);
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenGoogleKey = new AtomicReference<>();
    private String stubUrl;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            seenGoogleKey.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            ex.sendResponseHeaders(modelsStatus.get(), -1);
            ex.close();
        });
        server.start();
        stubUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void getReturnsSeededDefaultsWithoutKey() throws Exception {
        Login l = login("admin");
        mvc.perform(get("/api/settings/ai").session(l.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProvider").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.providers.length()").value(2))
                .andExpect(jsonPath("$.providers[0].provider").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.providers[0].baseUrl").value("https://integrate.api.nvidia.com/v1"))
                .andExpect(jsonPath("$.providers[0].model").value("z-ai/glm-5.3-flash"))
                .andExpect(jsonPath("$.providers[0].keyConfigured").value(false))
                .andExpect(jsonPath("$.providers[0].apiKeyHint").doesNotExist())
                .andExpect(jsonPath("$.providers[1].provider").value("GEMINI"))
                .andExpect(jsonPath("$.providers[1].baseUrl").value("https://generativelanguage.googleapis.com/v1beta"))
                .andExpect(jsonPath("$.providers[1].model").value("gemini-flash-latest"))
                .andExpect(jsonPath("$.providers[1].keyConfigured").value(false));
    }

    @Test
    void putStoresCiphertextAndReturnsHintOnly(CapturedOutput output) throws Exception {
        Login l = login("admin");
        String body = mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                        .content(json("https://llm.example.com/v1", "my-model", true, KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].model").value("my-model"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.providers[0].keyConfigured").value(true))
                .andExpect(jsonPath("$.providers[0].apiKeyHint").isNotEmpty())
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRETKEY");

        String read = mvc.perform(get("/api/settings/ai").session(l.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(read).doesNotContain("SECRETKEY");

        byte[] stored = jdbc.queryForObject("SELECT api_key_ciphertext FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'", byte[].class);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("SECRETKEY");
        assertThat(jdbc.queryForObject("SELECT api_key_hint FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'", String.class))
                .doesNotContain("SECRETKEY").hasSizeLessThanOrEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'", Object.class)).isNotNull();
        assertThat(output.getAll()).doesNotContain("SECRETKEY");
    }

    @Test
    void absentKeyKeepsStoredKeyAndEmptyKeyClearsIt() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://llm.example.com/v1", "m", true, KEY))).andExpect(status().isOk());
        byte[] before = jdbc.queryForObject("SELECT api_key_ciphertext FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'", byte[].class);

        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                        .content(json("https://llm.example.com/v1", "m2", false, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].model").value("m2"))
                .andExpect(jsonPath("$.providers[0].keyConfigured").value(true));
        assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'", byte[].class)).isEqualTo(before);

        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                        .content(json("https://llm.example.com/v1", "m2", false, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].keyConfigured").value(false))
                .andExpect(jsonPath("$.providers[0].apiKeyHint").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'", byte[].class)).isNull();
        assertThat(jdbc.queryForObject("SELECT api_key_hint FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'", String.class)).isNull();
    }

    @Test
    void putRejectsInvalidInput() throws Exception {
        Login l = login("admin");
        for (String bad : List.of(
                json("", "m", true, null),
                json("https://llm.example.com/v1", "", true, null),
                json("ftp://llm.example.com/v1", "m", true, null),
                json("not a url", "m", true, null),
                json("https://user:pw@llm.example.com/v1", "m", true, null),
                "{\"baseUrl\":\"https://llm.example.com/v1\",\"model\":\"m\",\"activeProvider\":\"GEMINI\"}",
                "{\"baseUrl\":\"https://llm.example.com/v1\",\"model\":\"m\",\"enabled\":true,\"activeProvider\":\"BOGUS\"}")) {
            mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT model FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'", String.class)).isEqualTo("z-ai/glm-5.3-flash");
    }

    @Test
    void invalidUrlHasStableCode() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                        .content(json("ftp://llm.example.com/v1", "m", true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BASE_URL"));
    }

    @Test
    void testReportsOkAndSendsBearerKey() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json(stubUrl, "m", true, KEY))).andExpect(status().isOk());
        mvc.perform(l.write(post("/api/settings/ai/test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
        assertThat(seenAuth.get()).isEqualTo("Bearer " + KEY);
    }

    @Test
    void testReportsAuthFailedFor401And403() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json(stubUrl, "m", true, KEY))).andExpect(status().isOk());
        for (int code : new int[]{401, 403}) {
            modelsStatus.set(code);
            mvc.perform(l.write(post("/api/settings/ai/test")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("AUTH_FAILED"));
        }
    }

    @Test
    void testReportsUnreachableForServerErrorAndClosedPort() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json(stubUrl, "m", true, KEY))).andExpect(status().isOk());
        modelsStatus.set(500);
        mvc.perform(l.write(post("/api/settings/ai/test")))
                .andExpect(jsonPath("$.status").value("UNREACHABLE"));

        server.stop(0);
        mvc.perform(l.write(post("/api/settings/ai/test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNREACHABLE"));
    }

    @Test
    void testWithoutStoredKeyIsRejected() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post("/api/settings/ai/test")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("API_KEY_REQUIRED"));
    }

    @Test
    void nonAdminsAreForbidden() throws Exception {
        for (String user : List.of("operator", "viewer")) {
            Login l = login(user);
            mvc.perform(get("/api/settings/ai").session(l.session())).andExpect(status().isForbidden());
            mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                    .content(json("https://llm.example.com/v1", "m", true, KEY))).andExpect(status().isForbidden());
            mvc.perform(l.write(post("/api/settings/ai/test"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/settings/ai")).andExpect(status().isUnauthorized());
    }

    @Test
    void updateIsAuditedWithoutSecrets() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://llm.example.com/v1", "m", true, KEY))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'AI_SETTINGS_UPDATE' AND outcome = 'SUCCESS' "
                        + "AND username = 'admin'", Integer.class)).isEqualTo(1);
        String all = jdbc.queryForObject(
                "SELECT string_agg(coalesce(target_id,'') || coalesce(details::text,''), ',') FROM audit_log",
                String.class);
        assertThat(all).doesNotContain("SECRETKEY");
    }

    @Test
    void activeConfigIsEmptyUntilEnabledWithKeyAndHidesKeyInToString() throws Exception {
        assertThat(service.activeConfig()).isEmpty();
        Login l = login("admin");

        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://llm.example.com/v1", "m", true, null))).andExpect(status().isOk());
        assertThat(service.activeConfig()).as("enabled but no key").isEmpty();

        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://llm.example.com/v1", "m", false, KEY))).andExpect(status().isOk());
        assertThat(service.activeConfig()).as("key but disabled").isEmpty();

        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://llm.example.com/v1", "m", true, null))).andExpect(status().isOk());
        AiConfig config = service.activeConfig().orElseThrow();
        assertThat(config.provider()).isEqualTo(AiProvider.OPENAI_COMPATIBLE);
        assertThat(config.apiKey()).isEqualTo(KEY);
        assertThat(config.baseUrl()).isEqualTo("https://llm.example.com/v1");
        assertThat(config.model()).isEqualTo("m");
        assertThat(config.toString()).doesNotContain("SECRETKEY");
    }

    @Test
    void switchingProviderKeepsBothKeysAndConfigs() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://llm.example.com/v1", "oa-model", true, KEY))).andExpect(status().isOk());
        String body = mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                        .content(json("GEMINI", "GEMINI", "https://gem.example.com/v1beta", "gem-model", true,
                                "AIzaGEMKEY1234567890abcd")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProvider").value("GEMINI"))
                .andExpect(jsonPath("$.providers[0].keyConfigured").value(true))
                .andExpect(jsonPath("$.providers[0].model").value("oa-model"))
                .andExpect(jsonPath("$.providers[1].keyConfigured").value(true))
                .andExpect(jsonPath("$.providers[1].model").value("gem-model"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRETKEY").doesNotContain("GEMKEY");

        AiConfig gemini = service.activeConfig().orElseThrow();
        assertThat(gemini.provider()).isEqualTo(AiProvider.GEMINI);
        assertThat(gemini.apiKey()).isEqualTo("AIzaGEMKEY1234567890abcd");
        assertThat(gemini.model()).isEqualTo("gem-model");

        // switch back without touching any key: the first provider's key is still there
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                        .content(json("OPENAI_COMPATIBLE", "OPENAI_COMPATIBLE", "https://llm.example.com/v1",
                                "oa-model", true, null)))
                .andExpect(status().isOk());
        AiConfig back = service.activeConfig().orElseThrow();
        assertThat(back.provider()).isEqualTo(AiProvider.OPENAI_COMPATIBLE);
        assertThat(back.apiKey()).isEqualTo(KEY);
    }

    @Test
    void providerFieldEditsAnotherProviderWithoutChangingKeysOfTheActiveOne() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://llm.example.com/v1", "m", true, KEY))).andExpect(status().isOk());
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                        .content(json("OPENAI_COMPATIBLE", "GEMINI", "https://gem.example.com/v1beta", "g2", true,
                                "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProvider").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.providers[0].keyConfigured").value(true))
                .andExpect(jsonPath("$.providers[1].model").value("g2"))
                .andExpect(jsonPath("$.providers[1].keyConfigured").value(false));
    }

    @Test
    void activeConfigIsEmptyWhenOnlyTheInactiveProviderHasAKey() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("https://llm.example.com/v1", "m", true, KEY))).andExpect(status().isOk());
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("GEMINI", "GEMINI", "https://gem.example.com/v1beta", "g", true, null)))
                .andExpect(status().isOk());
        assertThat(service.activeConfig()).isEmpty();
    }

    @Test
    void testOfProviderWithoutKeyIsRejected() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("OPENAI_COMPATIBLE", "GEMINI", "https://gem.example.com/v1beta", "g", true,
                        "AIzaGEMKEY1234567890abcd"))).andExpect(status().isOk());
        mvc.perform(l.write(post("/api/settings/ai/test")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("API_KEY_REQUIRED"));
    }

    @Test
    void geminiTestSendsGoogleKeyHeaderAndMapsStatuses() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("OPENAI_COMPATIBLE", "GEMINI", stubUrl, "g", true, KEY))).andExpect(status().isOk());
        String body = "{\"provider\":\"GEMINI\"}";
        mvc.perform(l.write(post("/api/settings/ai/test")).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK"));
        assertThat(seenGoogleKey.get()).isEqualTo(KEY);
        assertThat(seenAuth.get()).isNull();
        modelsStatus.set(403);
        mvc.perform(l.write(post("/api/settings/ai/test")).contentType(APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.status").value("AUTH_FAILED"));
        modelsStatus.set(500);
        mvc.perform(l.write(post("/api/settings/ai/test")).contentType(APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.status").value("UNREACHABLE"));
    }

    @Test
    void testWithProviderBodyTestsThatProvider() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/ai")).contentType(APPLICATION_JSON)
                .content(json("GEMINI", "OPENAI_COMPATIBLE", stubUrl, "m", true, KEY))).andExpect(status().isOk());
        mvc.perform(l.write(post("/api/settings/ai/test")).contentType(APPLICATION_JSON)
                        .content("{\"provider\":\"OPENAI_COMPATIBLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void requestToStringHidesKey() {
        assertThat(new AiSettingsRequest(true, AiProvider.GEMINI, null, "https://x/v1", "m", KEY).toString()).doesNotContain("SECRETKEY");
    }
}
