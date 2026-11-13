package dev.bethge.shipir.architecture.fixture.host;

import java.io.IOException;

/** Deliberate violation: spawns a subprocess through Runtime.exec. */
public class BadRuntimeExecUser {
    Process spawn() throws IOException {
        return Runtime.getRuntime().exec("ls");
    }
}
