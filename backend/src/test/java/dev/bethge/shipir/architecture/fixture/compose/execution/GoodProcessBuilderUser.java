package dev.bethge.shipir.architecture.fixture.compose.execution;

/** Allowed: constructing a ProcessBuilder is what compose.execution is for. */
public class GoodProcessBuilderUser {
    ProcessBuilder builder = new ProcessBuilder("docker", "compose", "config");
}
