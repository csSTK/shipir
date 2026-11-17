package dev.bethge.shipir.ai.chat;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.ai.llm.LlmClient;
import dev.bethge.shipir.ai.llm.LlmClients;
import dev.bethge.shipir.ai.llm.LlmMessage;
import dev.bethge.shipir.ai.llm.LlmResponse;
import dev.bethge.shipir.ai.llm.LlmToolCall;
import dev.bethge.shipir.ai.llm.LlmToolDefinition;
import dev.bethge.shipir.ai.settings.AiConfig;
import dev.bethge.shipir.ai.settings.AiProvider;
import dev.bethge.shipir.ai.settings.AiSettingsService;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The chat assistant without {@code shipir.encryption-key}: a write call that carries variables must not be
 * proposed (nothing to encrypt them with), so the model gets an ENCRYPTION_KEY_MISSING error and no value is stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, ChatOrchestratorNoEncryptionKeyTest.StubConfig.class})
@TestPropertySource(properties = {"shipir.encryption-key=",
        "shipir.stacks.base-dir=target/chat-orchestrator-no-key-stacks"})
class ChatOrchestratorNoEncryptionKeyTest {

    static final String PASSWORD = "A-test-Passw0rd-for-ai-chat!";
    static final String SECRET = "NoKey-Env-Value-4d7e";
    static final String YAML = "services:\n  web:\n    image: nginx\n";

    /** Replays queued answers and records every conversation it was given. */
    static class StubLlm implements LlmClient {
        final Queue<LlmResponse> script = new ConcurrentLinkedQueue<>();
        final List<List<LlmMessage>> conversations = new CopyOnWriteArrayList<>();

        @Override
        public LlmResponse chat(String baseUrl, String model, String apiKey, List<LlmMessage> messages,
                                List<LlmToolDefinition> tools, java.util.function.Consumer<String> onToken) {
            conversations.add(messages);
            LlmResponse next = script.poll();
            if (next == null) {
                throw new IllegalStateException("No scripted answer left");
            }
            return next;
        }

        void answer(String text) {
            script.add(new LlmResponse(text, List.of()));
        }

        void call(String id, String name, String args) {
            script.add(new LlmResponse("", List.of(new LlmToolCall(id, name, args))));
        }
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        StubLlm stubLlm() {
            return new StubLlm();
        }

        @Bean
        @Primary
        LlmClients stubLlmClients(StubLlm openAi) {
            return new LlmClients(openAi, new StubLlm());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired StubLlm llm;
    @Autowired ChatOrchestrator orchestrator;
    @MockitoBean AiSettingsService settings;

    @BeforeEach
    void reset() {
        Mockito.when(settings.activeConfig()).thenReturn(Optional.of(new AiConfig(AiProvider.OPENAI_COMPATIBLE, "https://llm.test/v1", "m", "k")));
        llm.script.clear();
        llm.conversations.clear();
        jdbc.update("DELETE FROM chat_sessions");
        jdbc.update("DELETE FROM deployments");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
    }

    record Login(MockHttpSession session, Cookie csrf) {
        MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
            return b.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    String newSession(Login l) throws Exception {
        String json = mvc.perform(l.write(post("/api/ai/sessions").contentType(APPLICATION_JSON).content("{}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("id").asString();
    }

    void say(Login l, String sessionId, String text) throws Exception {
        mvc.perform(l.write(post("/api/ai/sessions/" + sessionId + "/messages").contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("content", text)))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.runId").isNotEmpty());
        await().atMost(10, TimeUnit.SECONDS).until(() -> !orchestrator.isRunning(UUID.fromString(sessionId)));
    }

    JsonNode messages(Login l, String sessionId) throws Exception {
        String json = mvc.perform(get("/api/ai/sessions/" + sessionId).session(l.session()))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("messages");
    }

    String createArgs(String env) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("hostId", UUID.randomUUID().toString());
        args.put("name", "api");
        args.put("yaml", YAML);
        if (env != null) {
            args.put("env", env);
        }
        return mapper.writeValueAsString(args);
    }

    @Test
    void writeWithVariablesIsAnEncryptionKeyMissingErrorAndNothingIsStored() throws Exception {
        Login operator = login("operator");
        String id = newSession(operator);
        llm.call("call-1", "create_deployment", createArgs("DB_PASSWORD=" + SECRET + "\nMODE=prod"));
        llm.answer("I could not create it without a key.");

        say(operator, id, "Create a deployment with DB_PASSWORD");

        JsonNode msgs = messages(operator, id);
        assertThat(msgs).extracting(m -> m.get("role").asString())
                .containsExactly("USER", "ASSISTANT", "TOOL", "ASSISTANT");
        assertThat(msgs).extracting(m -> m.path("status").asString(null))
                .doesNotContain("PENDING");
        JsonNode toolResult = msgs.get(2);
        assertThat(toolResult.get("toolCallId").asString()).isEqualTo("call-1");
        assertThat(toolResult.get("content").asString()).contains("ENCRYPTION_KEY_MISSING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
        LlmMessage told = llm.conversations.get(1).getLast();
        assertThat(told.role()).isEqualTo(LlmMessage.Role.TOOL);
        assertThat(told.content()).contains("ENCRYPTION_KEY_MISSING");
        assertNoSecret(operator, id);
    }

    @Test
    void writeWithoutVariablesIsStillProposedWithoutAKey() throws Exception {
        Login operator = login("operator");
        String id = newSession(operator);
        llm.call("call-1", "create_deployment", createArgs(null));

        say(operator, id, "Create a deployment");

        JsonNode msgs = messages(operator, id);
        JsonNode pending = msgs.get(msgs.size() - 1);
        assertThat(pending.get("status").asString()).isEqualTo("PENDING");
        assertThat(pending.get("toolCalls").get(0).get("name").asString()).isEqualTo("create_deployment");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
    }

    private void assertNoSecret(Login l, String sessionId) throws Exception {
        String rows = jdbc.queryForObject("SELECT coalesce(string_agg(coalesce(content, '') || ' ' "
                + "|| coalesce(tool_calls::text, ''), ' '), '') FROM chat_messages", String.class);
        assertThat(rows).doesNotContain(SECRET);
        String api = mvc.perform(get("/api/ai/sessions/" + sessionId).session(l.session()))
                .andReturn().getResponse().getContentAsString();
        assertThat(api).doesNotContain(SECRET).doesNotContain("envCiphertext");
    }
}
