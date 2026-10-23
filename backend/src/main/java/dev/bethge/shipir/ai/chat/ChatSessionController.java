package dev.bethge.shipir.ai.chat;

import dev.bethge.shipir.ai.chat.dto.ChatSessionDetailResponse;
import dev.bethge.shipir.ai.chat.dto.ChatSessionPageResponse;
import dev.bethge.shipir.ai.chat.dto.ChatSessionResponse;
import dev.bethge.shipir.ai.chat.dto.CreateChatSessionRequest;
import dev.bethge.shipir.ai.chat.dto.UpdateChatSessionRequest;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.exception.ErrorResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Own sessions under {@code /api/ai/sessions} (every role); {@code /api/ai/shared-sessions} is ADMIN-only
 * (enforced in {@code SecurityConfig}) and read-only.
 */
@RestController
@RequestMapping("/api/ai")
class ChatSessionController {

    private final ChatSessionService service;
    private final UserRepository users;

    ChatSessionController(ChatSessionService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping("/sessions")
    ChatSessionPageResponse list(Authentication auth, @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return service.list(userId(auth), page, size);
    }

    @PostMapping("/sessions")
    ResponseEntity<ChatSessionResponse> create(Authentication auth,
                                               @Valid @RequestBody(required = false) CreateChatSessionRequest request) {
        ChatSessionResponse created = service.create(userId(auth), request == null ? null : request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/sessions/{id}")
    ChatSessionDetailResponse get(Authentication auth, @PathVariable UUID id) {
        return service.get(userId(auth), id);
    }

    @PatchMapping("/sessions/{id}")
    ChatSessionResponse update(Authentication auth, @PathVariable UUID id,
                               @Valid @RequestBody UpdateChatSessionRequest request) {
        UUID userId = userId(auth);
        ChatSessionResponse current = service.get(userId, id).session();
        if (request.title() != null) {
            current = service.rename(userId, id, request.title());
        }
        Boolean shared = request.sharedWithAdmin();
        if (shared != null && shared != current.sharedWithAdmin()) {
            current = shared ? service.share(userId, id) : service.unshare(userId, id);
        }
        return current;
    }

    @DeleteMapping("/sessions/{id}")
    ResponseEntity<Void> delete(Authentication auth, @PathVariable UUID id) {
        service.delete(userId(auth), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/shared-sessions")
    ChatSessionPageResponse listShared(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return service.listShared(page, size);
    }

    @GetMapping("/shared-sessions/{id}")
    ChatSessionDetailResponse getShared(@PathVariable UUID id) {
        return service.getShared(id);
    }

    private UUID userId(Authentication auth) {
        return users.findByUsername(auth.getName()).orElseThrow().getId();
    }

    @ExceptionHandler(ChatSessionException.class)
    ResponseEntity<ErrorResponse> handle(ChatSessionException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
