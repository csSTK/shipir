package dev.bethge.shipir.streaming;

import dev.bethge.shipir.auth.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Creates an interactive console in a container. SecurityConfig lets ADMIN and OPERATOR through; the handler
 * decides (OPERATOR needs the feature flag and an OPERATE grant). VIEWER is refused in SecurityConfig.
 */
@RestController
@RequestMapping("/api/hosts/{hostId}/containers/{containerId}/exec")
class ExecController {

    private final ExecSessionHandler sessions;

    private final StreamAccess access;

    ExecController(ExecSessionHandler sessions, StreamAccess access) {
        this.sessions = sessions;
        this.access = access;
    }

    @PostMapping
    ResponseEntity<ExecSessionResponse> create(@PathVariable UUID hostId, @PathVariable String containerId,
                                               @RequestBody(required = false) ExecRequest body,
                                               Authentication authentication, HttpServletRequest request) {
        HttpSession http = request.getSession(false);
        ExecRequest req = body == null ? new ExecRequest(null, null, null) : body;
        Role role = access.roleOf(authentication.getName()).orElseThrow(
                () -> new ExecException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied"));
        String id = sessions.start(hostId, containerId, req.shell(), req.cols(), req.rows(),
                authentication.getName(), role, http == null ? null : http.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ExecSessionResponse(id,
                ExecSessionHandler.OUTPUT_PREFIX + id, ExecSessionHandler.APP_PREFIX + id + "/input",
                ExecSessionHandler.APP_PREFIX + id + "/resize"));
    }
}
