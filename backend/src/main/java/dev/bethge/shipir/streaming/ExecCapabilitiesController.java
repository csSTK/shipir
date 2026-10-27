package dev.bethge.shipir.streaming;

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

    ExecCapabilitiesController(ExecSessionHandler sessions) {
        this.sessions = sessions;
    }

    /** Any signed-in user; the flag is a property, not a secret. Grants are still checked when a console starts. */
    @GetMapping("/capabilities")
    ExecCapabilities capabilities() {
        return new ExecCapabilities(sessions.operatorEnabled());
    }
}
