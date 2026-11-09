package dev.bethge.shipir.ai.chat;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ChatSessionApiTest {

    static final String PASSWORD = "A-test-Passw0rd-for-ai-chat!";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    ChatSessionService service;
    @Autowired
    ObjectMapper mapper;
    @MockitoSpyBean
    ChatOrchestrator orchestrator;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM chat_sessions");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Chat host', 'LOCAL', id FROM users WHERE username = 'admin'");
    }

    record Login(MockHttpSession session, Cookie csrf) {
        MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
            return b.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login")
                .cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    String create(Login l, String body) throws Exception {
        String json = mvc.perform(l.write(post("/api/ai/sessions").contentType(APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("id").asString();
    }

    UUID userId(String name) {
        return users.findByUsername(name).orElseThrow().getId();
    }

    int auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND outcome = 'SUCCESS'",
                Integer.class, action);
    }

    @Test
    void everyRoleCanCreateReadRenameAndDeleteOwnSession() throws Exception {
        for (String name : List.of("admin", "operator", "viewer")) {
            Login l = login(name);
            String id = create(l, "{\"title\":\"Hello\"}");
            mvc.perform(get("/api/ai/sessions/" + id).session(l.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.session.title").value("Hello"))
                    .andExpect(jsonPath("$.session.sharedWithAdmin").value(false))
                    .andExpect(jsonPath("$.messages.length()").value(0));
            mvc.perform(l.write(patch("/api/ai/sessions/" + id).contentType(APPLICATION_JSON)
                            .content("{\"title\":\"Renamed\"}")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Renamed"));
            mvc.perform(l.write(delete("/api/ai/sessions/" + id))).andExpect(status().isNoContent());
            mvc.perform(get("/api/ai/sessions/" + id).session(l.session())).andExpect(status().isNotFound());
        }
        assertThat(auditCount("CHAT_SESSION_DELETE")).isEqualTo(3);
    }

    @Test
    void sessionDetailReportsWhetherARunIsActive() throws Exception {
        Login l = login("operator");
        String id = create(l, "{}");
        mvc.perform(get("/api/ai/sessions/" + id).session(l.session()))
                .andExpect(jsonPath("$.running").value(false));
        org.mockito.Mockito.doReturn(true).when(orchestrator).isRunning(UUID.fromString(id));
        mvc.perform(get("/api/ai/sessions/" + id).session(l.session()))
                .andExpect(jsonPath("$.running").value(true));
    }

    @Test
    void createWithoutTitleOrBodyUsesDefault() throws Exception {
        Login l = login("viewer");
        String id = create(l, "{}");
        mvc.perform(l.write(post("/api/ai/sessions"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("New chat"));
        mvc.perform(get("/api/ai/sessions/" + id).session(l.session()))
                .andExpect(jsonPath("$.session.title").value("New chat"));
    }

    @Test
    void blankTitleOnUpdateIsRejected() throws Exception {
        Login l = login("operator");
        String id = create(l, "{}");
        mvc.perform(l.write(patch("/api/ai/sessions/" + id).contentType(APPLICATION_JSON)
                        .content("{\"title\":\"  \"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    @Test
    void otherUsersSessionIsNotFoundForEveryOperation() throws Exception {
        Login owner = login("operator");
        String id = create(owner, "{\"title\":\"Mine\"}");
        Login other = login("viewer");
        mvc.perform(get("/api/ai/sessions/" + id).session(other.session())).andExpect(status().isNotFound());
        mvc.perform(other.write(patch("/api/ai/sessions/" + id).contentType(APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"sharedWithAdmin\":true}"))).andExpect(status().isNotFound());
        mvc.perform(other.write(delete("/api/ai/sessions/" + id))).andExpect(status().isNotFound());
        mvc.perform(get("/api/ai/sessions").session(other.session()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/ai/sessions/" + id).session(owner.session()))
                .andExpect(jsonPath("$.session.title").value("Mine"))
                .andExpect(jsonPath("$.session.sharedWithAdmin").value(false));
    }

    @Test
    void adminSeesOnlySharedSessionsReadOnlyAndUnsharingRevokes() throws Exception {
        Login op = login("operator");
        String shared = create(op, "{\"title\":\"Shared\"}");
        String hidden = create(op, "{\"title\":\"Hidden\"}");
        service.appendMessage(UUID.fromString(shared), ChatRole.USER, "hi", null, null, null);
        Login admin = login("admin");

        mvc.perform(admin.write(patch("/api/ai/sessions/" + shared).contentType(APPLICATION_JSON)
                        .content("{\"sharedWithAdmin\":true}"))).andExpect(status().isNotFound());

        mvc.perform(op.write(patch("/api/ai/sessions/" + shared).contentType(APPLICATION_JSON)
                        .content("{\"sharedWithAdmin\":true}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sharedWithAdmin").value(true));
        assertThat(auditCount("CHAT_SESSION_SHARE")).isEqualTo(1);

        mvc.perform(get("/api/ai/shared-sessions").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Shared"))
                .andExpect(jsonPath("$.items[0].ownerUsername").value("operator"));
        mvc.perform(get("/api/ai/shared-sessions/" + shared).session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.ownerUsername").value("operator"))
                .andExpect(jsonPath("$.messages[0].content").value("hi"));
        mvc.perform(get("/api/ai/shared-sessions/" + hidden).session(admin.session()))
                .andExpect(status().isNotFound());

        // read-only: admin cannot write or delete through any route
        mvc.perform(admin.write(delete("/api/ai/sessions/" + shared))).andExpect(status().isNotFound());
        mvc.perform(admin.write(patch("/api/ai/sessions/" + shared).contentType(APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))).andExpect(status().isNotFound());
        mvc.perform(admin.write(delete("/api/ai/shared-sessions/" + shared))).andExpect(status().isMethodNotAllowed());
        mvc.perform(admin.write(patch("/api/ai/shared-sessions/" + shared).contentType(APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))).andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_sessions", Integer.class)).isEqualTo(2);

        mvc.perform(op.write(patch("/api/ai/sessions/" + shared).contentType(APPLICATION_JSON)
                        .content("{\"sharedWithAdmin\":false}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sharedWithAdmin").value(false));
        assertThat(auditCount("CHAT_SESSION_UNSHARE")).isEqualTo(1);
        mvc.perform(get("/api/ai/shared-sessions/" + shared).session(admin.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ai/shared-sessions").session(admin.session()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void repeatedShareDoesNotAuditAgain() throws Exception {
        Login op = login("operator");
        String id = create(op, "{}");
        String body = "{\"sharedWithAdmin\":true}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(op.write(patch("/api/ai/sessions/" + id).contentType(APPLICATION_JSON).content(body)))
                    .andExpect(status().isOk());
        }
        assertThat(auditCount("CHAT_SESSION_SHARE")).isEqualTo(1);
    }

    @Test
    void nonAdminsCannotReachSharedSessions() throws Exception {
        for (String name : List.of("operator", "viewer")) {
            Login l = login(name);
            mvc.perform(get("/api/ai/shared-sessions").session(l.session())).andExpect(status().isForbidden());
            mvc.perform(get("/api/ai/shared-sessions/" + UUID.randomUUID()).session(l.session()))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/ai/shared-sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/sessions")).andExpect(status().isUnauthorized());
    }

    @Test
    void listIsPagedNewestFirst() throws Exception {
        Login l = login("viewer");
        for (int i = 1; i <= 5; i++) {
            create(l, "{\"title\":\"s" + i + "\"}");
        }
        mvc.perform(get("/api/ai/sessions?page=0&size=2").session(l.session()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.items[0].title").value("s5"))
                .andExpect(jsonPath("$.items[1].title").value("s4"));
        mvc.perform(get("/api/ai/sessions?page=2&size=2").session(l.session()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("s1"));
    }

    @Test
    void detailReturnsMessagesInOrderWithToolCallsAsJson() throws Exception {
        Login l = login("operator");
        UUID id = UUID.fromString(create(l, "{}"));
        service.appendMessage(id, ChatRole.USER, "list hosts", null, null, null);
        service.appendMessage(id, ChatRole.ASSISTANT, null,
                "[{\"id\":\"c1\",\"name\":\"list_hosts\",\"arguments\":{}}]", null, ChatMessageStatus.PENDING);
        service.appendMessage(id, ChatRole.TOOL, "[]", null, "c1", null);

        MvcResult r = mvc.perform(get("/api/ai/sessions/" + id).session(l.session()))
                .andExpect(status().isOk()).andReturn();
        JsonNode msgs = mapper.readTree(r.getResponse().getContentAsString()).get("messages");
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(0).get("seq").asInt()).isEqualTo(1);
        assertThat(msgs.get(1).get("toolCalls").get(0).get("name").asString()).isEqualTo("list_hosts");
        assertThat(msgs.get(1).get("status").asString()).isEqualTo("PENDING");
        assertThat(msgs.get(2).get("toolCallId").asString()).isEqualTo("c1");
        assertThat(msgs.get(2).get("seq").asInt()).isEqualTo(3);
    }

    @Test
    void pendingCountCoversAllOwnSessionsAndNeverOthers() throws Exception {
        Login operator = login("operator");
        UUID first = UUID.fromString(create(operator, "{}"));
        UUID second = UUID.fromString(create(operator, "{}"));
        String call = "[{\"id\":\"c1\",\"name\":\"list_hosts\",\"arguments\":{}}]";
        service.appendMessage(first, ChatRole.ASSISTANT, null, call, null, ChatMessageStatus.PENDING);
        service.appendMessage(second, ChatRole.ASSISTANT, null, call, null, ChatMessageStatus.PENDING);
        service.appendMessage(second, ChatRole.ASSISTANT, null, call, null, ChatMessageStatus.CONFIRMED);

        mvc.perform(get("/api/ai/sessions/pending-count").session(operator.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
        mvc.perform(get("/api/ai/sessions/pending-count").session(login("viewer").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void serviceAppendAssignsNextSeqAndHistoryIsOrdered() {
        UUID owner = userId("viewer");
        UUID id = service.create(owner, "T").id();
        ChatMessage a = service.appendMessage(id, ChatRole.USER, "a", null, null, null);
        ChatMessage b = service.appendMessage(id, ChatRole.ASSISTANT, "b", null, null, null);
        assertThat(a.getSeq()).isEqualTo(1);
        assertThat(b.getSeq()).isEqualTo(2);
        assertThat(service.history(id)).extracting(ChatMessage::getContent).containsExactly("a", "b");
        assertThat(service.findOwned(owner, id).getId()).isEqualTo(id);
        UUID stranger = userId("admin");
        org.junit.jupiter.api.Assertions.assertThrows(ChatSessionException.class,
                () -> service.findOwned(stranger, id));
    }

    @Test
    void deletingSessionRemovesMessages() throws Exception {
        Login l = login("viewer");
        UUID id = UUID.fromString(create(l, "{}"));
        service.appendMessage(id, ChatRole.USER, "x", null, null, null);
        mvc.perform(l.write(delete("/api/ai/sessions/" + id))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages", Integer.class)).isZero();
    }
}
