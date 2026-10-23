package dev.bethge.shipir.ai.chat;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.ai.llm.LlmClient;
import dev.bethge.shipir.ai.llm.LlmException;
import dev.bethge.shipir.ai.llm.LlmMessage;
import dev.bethge.shipir.ai.llm.LlmResponse;
import dev.bethge.shipir.ai.llm.LlmToolCall;
import dev.bethge.shipir.ai.llm.LlmToolDefinition;
import dev.bethge.shipir.ai.settings.AiConfig;
import dev.bethge.shipir.ai.settings.AiSettingsService;
import dev.bethge.shipir.ai.tools.AiToolRegistry;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agent loop against Postgres, real tools and services, with a scripted LLM. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, ChatOrchestratorTest.StubConfig.class})
@TestPropertySource(properties = {"shipir.ai.chat.max-iterations=3",
        "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "shipir.stacks.base-dir=target/chat-orchestrator-stacks"})
class ChatOrchestratorTest {

    static final String PASSWORD = "A-test-Passw0rd-for-ai-chat!";

    /** Replays queued answers and records every conversation it was given. */
    static class StubLlm implements LlmClient {
        final ConcurrentLinkedQueue<Function<List<LlmMessage>, LlmResponse>> script = new ConcurrentLinkedQueue<>();
        final List<List<LlmMessage>> conversations = new CopyOnWriteArrayList<>();
        final List<List<LlmToolDefinition>> toolsOffered = new CopyOnWriteArrayList<>();

        @Override
        public LlmResponse chat(String baseUrl, String model, String apiKey, List<LlmMessage> messages,
                                List<LlmToolDefinition> tools, java.util.function.Consumer<String> onToken) {
            conversations.add(messages);
            toolsOffered.add(tools);
            Function<List<LlmMessage>, LlmResponse> next = script.poll();
            if (next == null) {
                throw new IllegalStateException("No scripted answer left");
            }
            return next.apply(messages);
        }

        void answer(String text) {
            script.add(m -> new LlmResponse(text, List.of()));
        }

        void call(String id, String name, String args) {
            script.add(m -> new LlmResponse("", List.of(new LlmToolCall(id, name, args))));
        }

        void fail(LlmException.Code code) {
            script.add(m -> {
                throw new LlmException(code, "boom");
            });
        }

        void reset() {
            script.clear();
            conversations.clear();
            toolsOffered.clear();
        }
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubLlm stubLlm() {
            return new StubLlm();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired StubLlm llm;
    @Autowired ChatOrchestrator orchestrator;
    @Autowired AiToolRegistry registry;
    @MockitoBean AiSettingsService settings;
    @MockitoBean dev.bethge.shipir.compose.execution.ComposeExecutionService execution;
    @MockitoBean dev.bethge.shipir.docker.client.DockerSystemService docker;

    UUID hostId;

    @BeforeEach
    void reset() {
        Mockito.when(settings.activeConfig()).thenReturn(Optional.of(new AiConfig("https://llm.test/v1", "m", "k")));
        llm.reset();
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM chat_sessions");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        var ok = new dev.bethge.shipir.compose.execution.ComposeProcessResult(0, false, List.of());
        Mockito.when(execution.execute(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(ok);
        Mockito.when(execution.execute(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any())).thenReturn(ok);
        jdbc.update("DELETE FROM stacks");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Chat host', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'Chat host'", UUID.class);
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
        awaitIdle(sessionId);
    }

    void awaitIdle(String sessionId) {
        await().atMost(10, TimeUnit.SECONDS).until(() -> !orchestrator.isRunning(UUID.fromString(sessionId)));
    }

    JsonNode messages(Login l, String sessionId) throws Exception {
        String json = mvc.perform(get("/api/ai/sessions/" + sessionId).session(l.session()))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("messages");
    }

    JsonNode action(Login l, String sessionId, String messageId, String verb, int expectedStatus) throws Exception {
        String json = mvc.perform(l.write(post("/api/ai/sessions/%s/actions/%s/%s".formatted(sessionId, messageId, verb))))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        awaitIdle(sessionId);
        return mapper.readTree(json);
    }

    String hostName() {
        return jdbc.queryForObject("SELECT name FROM docker_hosts WHERE id = ?", String.class, hostId);
    }

    String renameArgs() {
        return "{\"hostId\":\"%s\",\"name\":\"Renamed by AI\"}".formatted(hostId);
    }

    String pendingId(JsonNode messages) {
        for (JsonNode m : messages) {
            if ("PENDING".equals(m.path("status").asString(null))) {
                return m.get("id").asString();
            }
        }
        throw new AssertionError("no pending message in " + messages);
    }

    @Test
    void plainAnswerIsPersistedWithSystemPromptAndAutoTitle() throws Exception {
        Login l = login("viewer");
        String id = newSession(l);
        llm.answer("Hello there");
        say(l, id, "Hi, who are you?");

        JsonNode msgs = messages(l, id);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).get("role").asString()).isEqualTo("USER");
        assertThat(msgs.get(1).get("role").asString()).isEqualTo("ASSISTANT");
        assertThat(msgs.get(1).get("content").asString()).isEqualTo("Hello there");
        List<LlmMessage> sent = llm.conversations.get(0);
        assertThat(sent.get(0).role()).isEqualTo(LlmMessage.Role.SYSTEM);
        assertThat(sent.get(0).content()).contains("confirm").containsIgnoringCase("secrets");
        mvc.perform(get("/api/ai/sessions/" + id).session(l.session()))
                .andExpect(jsonPath("$.session.title").value("Hi, who are you?"));
    }

    @Test
    void readToolIsExecutedInTheLoopAndResultGoesBackToTheModel() throws Exception {
        Login l = login("viewer");
        String id = newSession(l);
        llm.call("call-1", "list_hosts", "{}");
        llm.answer("You have one environment.");
        say(l, id, "What environments do I have?");

        JsonNode msgs = messages(l, id);
        assertThat(msgs).extracting(m -> m.get("role").asString())
                .containsExactly("USER", "ASSISTANT", "TOOL", "ASSISTANT");
        assertThat(msgs.get(1).get("toolCalls").get(0).get("name").asString()).isEqualTo("list_hosts");
        assertThat(msgs.get(2).get("toolCallId").asString()).isEqualTo("call-1");
        assertThat(msgs.get(2).get("content").asString()).contains("Chat host");
        LlmMessage toolMessage = llm.conversations.get(1).getLast();
        assertThat(toolMessage.role()).isEqualTo(LlmMessage.Role.TOOL);
        assertThat(toolMessage.toolCallId()).isEqualTo("call-1");
        assertThat(toolMessage.content()).contains("Chat host");
    }

    @Test
    void writeToolIsPendingWithoutSideEffectUntilConfirmedThenLoopResumes() throws Exception {
        Login l = login("admin");
        String id = newSession(l);
        llm.call("call-1", "rename_host", renameArgs());
        llm.answer("Done, renamed.");
        say(l, id, "Rename my host");

        JsonNode msgs = messages(l, id);
        assertThat(hostName()).isEqualTo("Chat host");
        assertThat(llm.conversations).hasSize(1);
        JsonNode pending = msgs.get(msgs.size() - 1);
        assertThat(pending.get("status").asString()).isEqualTo("PENDING");
        assertThat(pending.get("toolCalls").get(0).get("name").asString()).isEqualTo("rename_host");
        assertThat(pending.get("toolCalls").get(0).get("token").asString()).isNotBlank();

        JsonNode result = action(l, id, pending.get("id").asString(), "confirm", 200);
        assertThat(result.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(result.get("error").asBoolean()).isFalse();
        assertThat(result.get("runId").asString()).isNotBlank();
        assertThat(hostName()).isEqualTo("Renamed by AI");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'HOST_RENAME' "
                + "AND details::text LIKE '%AI%'", Integer.class)).isEqualTo(1);

        JsonNode after = messages(l, id);
        assertThat(after.get(after.size() - 1).get("content").asString()).isEqualTo("Done, renamed.");
        assertThat(llm.conversations.get(1).getLast().content()).contains("Renamed by AI");
        action(l, id, pending.get("id").asString(), "confirm", 409);
    }

    @Test
    void rejectRecordsOutcomeAndTellsTheModel() throws Exception {
        Login l = login("admin");
        String id = newSession(l);
        llm.call("call-1", "rename_host", renameArgs());
        llm.answer("Okay, I will leave it.");
        say(l, id, "Rename my host");
        String pending = pendingId(messages(l, id));

        JsonNode result = action(l, id, pending, "reject", 200);

        assertThat(result.get("status").asString()).isEqualTo("REJECTED");
        assertThat(hostName()).isEqualTo("Chat host");
        LlmMessage told = llm.conversations.get(1).getLast();
        assertThat(told.role()).isEqualTo(LlmMessage.Role.TOOL);
        assertThat(told.content()).contains("rejected");
        JsonNode after = messages(l, id);
        assertThat(after.get(after.size() - 1).get("content").asString()).isEqualTo("Okay, I will leave it.");
        action(l, id, pending, "reject", 409);
    }

    @Test
    void confirmByAnotherUserIsNotFoundAndHasNoEffect() throws Exception {
        Login owner = login("admin");
        String id = newSession(owner);
        llm.call("call-1", "rename_host", renameArgs());
        say(owner, id, "Rename my host");
        String pending = pendingId(messages(owner, id));

        Login other = login("operator");
        action(other, id, pending, "confirm", 404);
        action(other, id, pending, "reject", 404);
        mvc.perform(other.write(post("/api/ai/sessions/" + id + "/messages").contentType(APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))).andExpect(status().isNotFound());

        assertThat(hostName()).isEqualTo("Chat host");
        assertThat(pendingId(messages(owner, id))).isEqualTo(pending);
    }

    @Test
    void confirmationSurvivesLostPendingStateByProposingAgain() throws Exception {
        Login l = login("admin");
        String id = newSession(l);
        llm.call("call-1", "rename_host", renameArgs());
        llm.answer("Renamed.");
        say(l, id, "Rename my host");
        String pending = pendingId(messages(l, id));
        // simulate a restart: the registry forgot every pending action
        ((Map<?, ?>) ReflectionTestUtils.getField(registry, "pending")).clear();

        JsonNode result = action(l, id, pending, "confirm", 200);

        assertThat(result.get("error").asBoolean()).isFalse();
        assertThat(hostName()).isEqualTo("Renamed by AI");
    }

    @Test
    void roleIsRecheckedWhenConfirming() throws Exception {
        Login admin = login("admin");
        String id = newSession(admin);
        llm.call("call-1", "rename_host", renameArgs());
        say(admin, id, "Rename my host");
        String pending = pendingId(messages(admin, id));
        ((Map<?, ?>) ReflectionTestUtils.getField(registry, "pending")).clear();
        jdbc.update("UPDATE users SET role = 'VIEWER' WHERE username = 'admin'");
        try {
            JsonNode result = action(admin, id, pending, "confirm", 200);
            assertThat(result.get("error").asBoolean()).isTrue();
            assertThat(hostName()).isEqualTo("Chat host");
        } finally {
            jdbc.update("UPDATE users SET role = 'ADMIN' WHERE username = 'admin'");
        }
    }

    @Test
    void writeToolAboveTheRoleIsAnErrorForTheModelNotAPendingAction() throws Exception {
        Login l = login("operator");
        String id = newSession(l);
        llm.call("call-1", "rename_host", renameArgs());
        llm.answer("I am not allowed to do that.");
        say(l, id, "Rename my host");

        JsonNode msgs = messages(l, id);
        assertThat(msgs).extracting(m -> m.path("status").asString(null)).containsOnlyNulls();
        assertThat(msgs.get(2).get("content").asString()).contains("UNKNOWN_TOOL");
        assertThat(llm.toolsOffered.get(0)).extracting(LlmToolDefinition::name).doesNotContain("rename_host");
        assertThat(hostName()).isEqualTo("Chat host");
    }

    @Test
    void iterationCapEndsTheLoop() throws Exception {
        Login l = login("viewer");
        String id = newSession(l);
        for (int i = 0; i < 5; i++) {
            llm.call("call-" + i, "list_hosts", "{}");
        }
        say(l, id, "Loop forever");

        assertThat(llm.conversations).hasSize(3);
        JsonNode msgs = messages(l, id);
        assertThat(msgs.get(msgs.size() - 1).get("role").asString()).isEqualTo("ASSISTANT");
        assertThat(msgs.get(msgs.size() - 1).get("content").asString()).contains("stopped after 3 steps");
    }

    @Test
    void llmErrorIsPersistedAsErrorMessageAndChatContinues() throws Exception {
        Login l = login("viewer");
        String id = newSession(l);
        llm.fail(LlmException.Code.UNREACHABLE);
        say(l, id, "Hello?");

        JsonNode msgs = messages(l, id);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(1).get("content").asString()).contains("UNREACHABLE").doesNotContain("boom");

        llm.answer("Back again");
        say(l, id, "Try again");
        JsonNode after = messages(l, id);
        assertThat(after.get(after.size() - 1).get("content").asString()).isEqualTo("Back again");
    }

    @Test
    void notConfiguredIsConflictAndStoresNothing() throws Exception {
        Mockito.when(settings.activeConfig()).thenReturn(Optional.empty());
        Login l = login("viewer");
        String id = newSession(l);
        mvc.perform(l.write(post("/api/ai/sessions/" + id + "/messages").contentType(APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED"));
        assertThat(messages(l, id)).isEmpty();
    }

    @Test
    void secondRunWhileOneIsActiveIsRejected() throws Exception {
        Login l = login("viewer");
        String id = newSession(l);
        CountDownLatch release = new CountDownLatch(1);
        llm.script.add(m -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LlmResponse("late", List.of());
        });
        mvc.perform(l.write(post("/api/ai/sessions/" + id + "/messages").contentType(APPLICATION_JSON)
                .content("{\"content\":\"one\"}"))).andExpect(status().isAccepted());
        mvc.perform(l.write(post("/api/ai/sessions/" + id + "/messages").contentType(APPLICATION_JSON)
                        .content("{\"content\":\"two\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHAT_RUN_IN_PROGRESS"));
        release.countDown();
        awaitIdle(id);
        assertThat(messages(l, id)).hasSize(2);
    }

    @Test
    void sendingWhileAnActionIsPendingIsRejected() throws Exception {
        Login l = login("admin");
        String id = newSession(l);
        llm.call("call-1", "rename_host", renameArgs());
        say(l, id, "Rename my host");
        mvc.perform(l.write(post("/api/ai/sessions/" + id + "/messages").contentType(APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTION_PENDING"));
    }

    @Test
    void blankMessageIsABadRequest() throws Exception {
        Login l = login("viewer");
        String id = newSession(l);
        mvc.perform(l.write(post("/api/ai/sessions/" + id + "/messages").contentType(APPLICATION_JSON)
                .content("{\"content\":\"  \"}"))).andExpect(status().isBadRequest());
    }

    @Test
    void sessionReloadsInOrderAndTheChatContinuesWithFullHistory() throws Exception {
        Login l = login("admin");
        String id = newSession(l);
        llm.call("call-1", "rename_host", renameArgs());
        llm.answer("Renamed.");
        llm.answer("Second answer");
        say(l, id, "Rename my host");
        action(l, id, pendingId(messages(l, id)), "confirm", 200);
        say(l, id, "Thanks, anything else?");

        JsonNode msgs = messages(l, id);
        assertThat(msgs).extracting(m -> m.get("role").asString())
                .containsExactly("USER", "ASSISTANT", "TOOL", "ASSISTANT", "USER", "ASSISTANT");
        assertThat(msgs).extracting(m -> m.get("seq").asInt()).containsExactly(1, 2, 3, 4, 5, 6);
        List<LlmMessage> last = llm.conversations.getLast();
        assertThat(last).extracting(LlmMessage::role).containsExactly(LlmMessage.Role.SYSTEM, LlmMessage.Role.USER,
                LlmMessage.Role.ASSISTANT, LlmMessage.Role.TOOL, LlmMessage.Role.ASSISTANT, LlmMessage.Role.USER);
        assertThat(last.get(2).toolCalls()).hasSize(1);
        assertThat(last.get(2).toolCalls().get(0).name()).isEqualTo("rename_host");
    }

    @Test
    void stackEnvValuesAreNeverStoredReturnedOrReplayedToTheModel() throws Exception {
        String secret = "S3cr3t-Env-Value-9f2c";
        String args = mapper.writeValueAsString(Map.of("hostId", hostId.toString(), "name", "api",
                "yaml", "services:\n  web:\n    image: nginx\n", "env", "DB_PASSWORD=" + secret));
        Login owner = login("operator");
        String id = newSession(owner);
        llm.call("call-1", "create_stack", args);
        llm.answer("Created.");
        say(owner, id, "Create a stack");
        String pending = pendingId(messages(owner, id));
        assertNoSecret(owner, id, secret);

        // restart: the real arguments must come back from the encrypted copy
        ((Map<?, ?>) ReflectionTestUtils.getField(registry, "pending")).clear();
        JsonNode result = action(owner, id, pending, "confirm", 200);

        assertThat(result.get("error").asBoolean()).as(result.toString()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions WHERE env_ciphertext IS NOT NULL",
                Integer.class)).isEqualTo(1);
        assertNoSecret(owner, id, secret);
        for (LlmMessage m : llm.conversations.getLast()) {
            assertThat(String.valueOf(m)).doesNotContain(secret);
        }
        assertThat(llm.conversations.getLast().toString()).contains("<hidden>");

        mvc.perform(owner.write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/ai/sessions/" + id).contentType(APPLICATION_JSON).content("{\"sharedWithAdmin\":true}")))
                .andExpect(status().isOk());
        Login admin = login("admin");
        String shared = mvc.perform(get("/api/ai/shared-sessions/" + id).session(admin.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(shared).doesNotContain(secret).doesNotContain("envCiphertext").contains("<hidden>");
    }

    private void assertNoSecret(Login l, String sessionId, String secret) throws Exception {
        String rows = jdbc.queryForObject("SELECT coalesce(string_agg(coalesce(content, '') || ' ' "
                + "|| coalesce(tool_calls::text, ''), ' '), '') FROM chat_messages", String.class);
        assertThat(rows).doesNotContain(secret);
        String api = mvc.perform(get("/api/ai/sessions/" + sessionId).session(l.session()))
                .andReturn().getResponse().getContentAsString();
        assertThat(api).doesNotContain(secret).doesNotContain("envCiphertext");
    }
}
