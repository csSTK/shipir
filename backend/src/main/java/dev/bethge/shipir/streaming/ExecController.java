package dev.bethge.shipir.streaming;

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

/** Creates an interactive console in a container (ADMIN only, enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/hosts/{hostId}/containers/{containerId}/exec")
class ExecController {

    private final ExecSessionHandler sessions;

    ExecController(ExecSessionHandler sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    ResponseEntity<ExecSessionResponse> create(@PathVariable UUID hostId, @PathVariable String containerId,
                                               @RequestBody(required = false) ExecRequest body,
                                               Authentication authentication, HttpServletRequest request) {
        HttpSession http = request.getSession(false);
        ExecRequest req = body == null ? new ExecRequest(null, null, null) : body;
        String id = sessions.start(hostId, containerId, req.shell(), req.cols(), req.rows(),
                authentication.getName(), http == null ? null : http.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ExecSessionResponse(id,
                ExecSessionHandler.OUTPUT_PREFIX + id, ExecSessionHandler.APP_PREFIX + id + "/input",
                ExecSessionHandler.APP_PREFIX + id + "/resize"));
    }
}
