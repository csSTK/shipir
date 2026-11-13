package dev.bethge.shipir.architecture.fixture.host;

import java.io.IOException;

/** Deliberate violation: takes Runtime.exec as a method reference, which a call-only rule misses. */
public class BadRuntimeExecReferenceUser {
    interface Spawner {
        Process spawn(Runtime runtime, String command) throws IOException;
    }

    Spawner spawner = Runtime::exec;
}
