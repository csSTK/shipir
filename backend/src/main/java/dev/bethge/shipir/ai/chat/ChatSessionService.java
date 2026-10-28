package dev.bethge.shipir.ai.chat;

import dev.bethge.shipir.ai.chat.dto.ChatMessageResponse;
import dev.bethge.shipir.ai.chat.dto.ChatSessionDetailResponse;
import dev.bethge.shipir.ai.chat.dto.ChatSessionPageResponse;
import dev.bethge.shipir.ai.chat.dto.ChatSessionResponse;
import dev.bethge.shipir.ai.chat.dto.PendingActionCountResponse;
import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chat sessions and their messages. Every owner-facing method takes the caller's user id and answers
 * "not found" for other users' sessions, so existence is never revealed. The ADMIN view only reaches
 * sessions the owner shared and is read-only.
 */
@Service
public class ChatSessionService {

    static final String HIDDEN = "<hidden>";
    static final String DEFAULT_TITLE = "New chat";
    private static final int MAX_PAGE_SIZE = 100;

    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final UserRepository users;
    private final ObjectMapper mapper;
    private final Clock clock;

    ChatSessionService(ChatSessionRepository sessions, ChatMessageRepository messages, UserRepository users,
                       ObjectMapper mapper, Clock clock) {
        this.sessions = sessions;
        this.messages = messages;
        this.users = users;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public ChatSessionResponse create(UUID userId, String title) {
        String effective = title == null || title.isBlank() ? DEFAULT_TITLE : title.strip();
        return toResponse(sessions.save(new ChatSession(userId, effective, clock.instant())), null);
    }

    @Transactional(readOnly = true)
    public ChatSessionPageResponse list(UUID userId, int page, int size) {
        return toPage(sessions.findByUserId(userId, pageRequest(page, size)), false);
    }

    @Transactional(readOnly = true)
    public PendingActionCountResponse pendingCount(UUID userId) {
        return new PendingActionCountResponse(messages.countPendingByUserId(userId));
    }

    @Transactional(readOnly = true)
    public ChatSessionDetailResponse get(UUID userId, UUID id) {
        return detail(findOwned(userId, id), null);
    }

    /** Public for the chat orchestration: the caller's own session or 404. */
    @Transactional(readOnly = true)
    public ChatSession findOwned(UUID userId, UUID id) {
        return sessions.findByIdAndUserId(id, userId).orElseThrow(ChatSessionException::notFound);
    }

    @Transactional
    public ChatSessionResponse rename(UUID userId, UUID id, String title) {
        if (title.isBlank()) {
            throw new ChatSessionException(HttpStatus.BAD_REQUEST, "INVALID_TITLE", "The title must not be blank");
        }
        ChatSession session = findOwned(userId, id);
        session.rename(title.strip(), clock.instant());
        return toResponse(session, null);
    }

    @Auditable(action = "CHAT_SESSION_SHARE", targetType = "CHAT_SESSION", targetId = "#id")
    @Transactional
    public ChatSessionResponse share(UUID userId, UUID id) {
        return setShared(userId, id, true);
    }

    @Auditable(action = "CHAT_SESSION_UNSHARE", targetType = "CHAT_SESSION", targetId = "#id")
    @Transactional
    public ChatSessionResponse unshare(UUID userId, UUID id) {
        return setShared(userId, id, false);
    }

    @Auditable(action = "CHAT_SESSION_DELETE", targetType = "CHAT_SESSION", targetId = "#id")
    @Transactional
    public void delete(UUID userId, UUID id) {
        sessions.delete(findOwned(userId, id));
    }

    /** Appends with the next sequence number and bumps the session's {@code updated_at}. */
    @Transactional
    public ChatMessage appendMessage(UUID sessionId, ChatRole role, String content, String toolCallsJson,
                                     String toolCallId, ChatMessageStatus status) {
        Instant now = clock.instant();
        ChatSession session = sessions.findById(sessionId).orElseThrow(ChatSessionException::notFound);
        ChatMessage message = messages.save(new ChatMessage(sessionId, messages.maxSeq(sessionId) + 1, role,
                content, toolCallsJson, toolCallId, status, now));
        session.touch(now);
        return message;
    }

    /** All messages of the session in conversation order. */
    @Transactional(readOnly = true)
    public List<ChatMessage> history(UUID sessionId) {
        return messages.findBySessionIdOrderBySeqAsc(sessionId);
    }

    /** The message if it belongs to the session, else 404. */
    @Transactional(readOnly = true)
    public ChatMessage findMessage(UUID sessionId, UUID messageId) {
        return messages.findById(messageId).filter(m -> m.getSessionId().equals(sessionId))
                .orElseThrow(() -> new ChatSessionException(HttpStatus.NOT_FOUND, "CHAT_ACTION_NOT_FOUND",
                        "Action not found"));
    }

    /** Settles a pending action message with its new status and outcome text. */
    @Transactional
    public void resolvePending(UUID messageId, ChatMessageStatus status, String outcome) {
        messages.findById(messageId).orElseThrow(ChatSessionException::notFound).resolve(status, outcome);
    }

    /** Names a still-untitled session after its first message. */
    @Transactional
    public void titleFromFirstMessage(UUID sessionId, String text) {
        ChatSession session = sessions.findById(sessionId).orElseThrow(ChatSessionException::notFound);
        String oneLine = text.strip().replaceAll("\\s+", " ");
        if (DEFAULT_TITLE.equals(session.getTitle()) && !oneLine.isEmpty()) {
            session.rename(oneLine.length() > 60 ? oneLine.substring(0, 57) + "..." : oneLine, clock.instant());
        }
    }

    @Transactional(readOnly = true)
    public ChatSessionPageResponse listShared(int page, int size) {
        return toPage(sessions.findBySharedWithAdminTrue(pageRequest(page, size)), true);
    }

    @Transactional(readOnly = true)
    public ChatSessionDetailResponse getShared(UUID id) {
        ChatSession session = sessions.findByIdAndSharedWithAdminTrue(id).orElseThrow(ChatSessionException::notFound);
        return detail(session, ownerName(session.getUserId()));
    }

    private ChatSessionResponse setShared(UUID userId, UUID id, boolean shared) {
        ChatSession session = findOwned(userId, id);
        session.setSharedWithAdmin(shared, clock.instant());
        return toResponse(session, null);
    }

    private PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    private ChatSessionPageResponse toPage(Page<ChatSession> p, boolean withOwner) {
        Map<UUID, String> owners = withOwner
                ? users.findAllById(p.getContent().stream().map(ChatSession::getUserId).distinct().toList()).stream()
                        .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a))
                : Map.of();
        List<ChatSessionResponse> items = p.getContent().stream()
                .map(s -> toResponse(s, owners.get(s.getUserId()))).toList();
        return new ChatSessionPageResponse(items, p.getNumber(), p.getSize(), p.getTotalElements());
    }

    private ChatSessionDetailResponse detail(ChatSession session, String owner) {
        return new ChatSessionDetailResponse(toResponse(session, owner),
                messages.findBySessionIdOrderBySeqAsc(session.getId()).stream().map(this::toResponse).toList());
    }

    private String ownerName(UUID userId) {
        return users.findById(userId).map(User::getUsername).orElse(null);
    }

    private static ChatSessionResponse toResponse(ChatSession s, String owner) {
        return new ChatSessionResponse(s.getId(), s.getTitle(), s.isSharedWithAdmin(), s.getCreatedAt(),
                s.getUpdatedAt(), owner);
    }

    /** Secrets never leave the service: drops the stored env ciphertext and hides any env value. */
    private JsonNode redact(JsonNode toolCalls) {
        for (JsonNode call : toolCalls) {
            if (call instanceof tools.jackson.databind.node.ObjectNode o) {
                o.remove("envCiphertext");
                o.remove("thoughtSignature");
                String args = o.path("arguments").asString(null);
                if (args != null && args.contains("\"env\"")) {
                    JsonNode parsed = mapper.readTree(args);
                    if (parsed instanceof tools.jackson.databind.node.ObjectNode a && a.has("env")) {
                        a.put("env", HIDDEN);
                        o.put("arguments", a.toString());
                    }
                }
            }
        }
        return toolCalls;
    }

    private ChatMessageResponse toResponse(ChatMessage m) {
        JsonNode toolCalls = m.getToolCalls() == null ? null : redact(mapper.readTree(m.getToolCalls()));
        return new ChatMessageResponse(m.getId(), m.getSeq(), m.getRole().name(), m.getContent(), toolCalls,
                m.getToolCallId(), m.getStatus() == null ? null : m.getStatus().name(), m.getCreatedAt());
    }
}
