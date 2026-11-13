package dev.bethge.shipir.architecture.fixture.host;

/** Deliberate violation: spawns a subprocess outside compose.execution. */
public class BadProcessBuilderUser {
    ProcessBuilder builder = new ProcessBuilder("ls");
}
