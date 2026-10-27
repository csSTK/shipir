package dev.bethge.shipir.ai.chat;

import dev.bethge.shipir.ai.chat.dto.ChatActionResponse;
import dev.bethge.shipir.ai.chat.dto.ChatRunResponse;
import dev.bethge.shipir.ai.chat.dto.SendChatMessageRequest;
import dev.bethge.shipir.ai.tools.ToolContext;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.exception.ErrorResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Talking to the assistant: send a message (async run, live output on {@code /topic/chat/{id}}), confirm or reject
 * a pending write action. Every role may chat; what the assistant can do is bounded by the caller's role.
 */
@RestController
@RequestMapping("/api/ai/sessions/{id}")
class ChatRunController {

    private final ChatOrchestrator orchestrator;
    private final UserRepository users;

    ChatRunController(ChatOrchestrator orchestrator, UserRepository users) {
        this.orchestrator = orchestrator;
        this.users = users;
    }

    @PostMapping("/messages")
    ResponseEntity<ChatRunResponse> send(Authentication auth, @PathVariable UUID id,
                                         @Valid @RequestBody SendChatMessageRequest request) {
        UUID runId = orchestrator.send(context(auth), id, request.content(), request.context());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ChatRunResponse(runId));
    }

    @PostMapping("/actions/{messageId}/confirm")
    ChatActionResponse confirm(Authentication auth, @PathVariable UUID id, @PathVariable UUID messageId) {
        return toResponse(orchestrator.confirm(context(auth), id, messageId));
    }

    @PostMapping("/actions/{messageId}/reject")
    ChatActionResponse reject(Authentication auth, @PathVariable UUID id, @PathVariable UUID messageId) {
        return toResponse(orchestrator.reject(context(auth), id, messageId));
    }

    private static ChatActionResponse toResponse(ChatOrchestrator.ActionOutcome o) {
        return new ChatActionResponse(o.messageId(), o.status().name(), o.error(), o.result(), o.runId());
    }

    private ToolContext context(Authentication auth) {
        User user = users.findByUsername(auth.getName()).orElseThrow();
        return new ToolContext(user.getId(), user.getUsername(), user.getRole());
    }

    @ExceptionHandler(ChatSessionException.class)
    ResponseEntity<ErrorResponse> handle(ChatSessionException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
