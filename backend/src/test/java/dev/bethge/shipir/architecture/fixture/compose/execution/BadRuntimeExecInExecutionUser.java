package dev.bethge.shipir.architecture.fixture.compose.execution;

import java.io.IOException;

/** Deliberate violation: Runtime.exec is forbidden everywhere, including compose.execution. */
public class BadRuntimeExecInExecutionUser {
    Process spawn() throws IOException {
        return Runtime.getRuntime().exec(new String[] {"docker", "ps"});
    }
}
