package dev.bethge.shipir.notification;

import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.notification.dto.NotificationPageResponse;
import dev.bethge.shipir.notification.dto.NotificationPreferenceDto;
import dev.bethge.shipir.notification.dto.UnreadCountResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Every endpoint works on the caller's own notifications only. */
@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    private final NotificationService service;
    private final UserRepository users;

    NotificationController(NotificationService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    NotificationPageResponse list(Authentication auth,
                                  @RequestParam(defaultValue = "false") boolean unreadOnly,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return service.list(userId(auth), unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    UnreadCountResponse unreadCount(Authentication auth) {
        return new UnreadCountResponse(service.unreadCount(userId(auth)));
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> markRead(Authentication auth, @PathVariable UUID id) {
        if (!service.markRead(id, userId(auth))) {
            throw new NotificationNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    ResponseEntity<Void> markAllRead(Authentication auth) {
        service.markAllRead(userId(auth));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    List<NotificationPreferenceDto> preferences(Authentication auth) {
        return service.preferences(userId(auth));
    }

    @PutMapping("/preferences")
    List<NotificationPreferenceDto> updatePreferences(Authentication auth,
                                                      @RequestBody List<@Valid NotificationPreferenceDto> body) {
        return service.updatePreferences(userId(auth), body);
    }

    private UUID userId(Authentication auth) {
        return users.findByUsername(auth.getName()).orElseThrow().getId();
    }

    static class NotificationNotFoundException extends RuntimeException {
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOTIFICATION_NOT_FOUND", "Notification not found"));
    }
}
