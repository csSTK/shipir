package dev.bethge.shipir.streaming;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI whether OPERATORs may open container consoles ({@code shipir.streaming.exec.operator-enabled}). */
@RestController
@RequestMapping("/api/exec")
class ExecCapabilitiesController {

    record ExecCapabilities(boolean operatorEnabled) {
    }

    private final ExecSessionHandler sessions;
    private final StreamAccess access;

    ExecCapabilitiesController(ExecSessionHandler sessions, StreamAccess access) {
        this.sessions = sessions;
        this.access = access;
    }

    /**
     * Where the signed-in user may open consoles on a host (UI hint only; starting a console re-checks everything).
     * Reveals nothing beyond the user's own grants.
     */
    @GetMapping("/access")
    ExecScope access(@RequestParam java.util.UUID hostId, Authentication authentication) {
        return access.execScope(authentication.getName(), hostId, sessions.operatorEnabled());
    }

    /** Any signed-in user; the flag is a property, not a secret. Grants are still checked when a console starts. */
    @GetMapping("/capabilities")
    ExecCapabilities capabilities() {
        return new ExecCapabilities(sessions.operatorEnabled());
    }
}
