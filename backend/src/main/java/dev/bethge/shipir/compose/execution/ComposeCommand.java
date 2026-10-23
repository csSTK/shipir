package dev.bethge.shipir.compose.execution;

import java.util.List;

/** The compose subcommands Shipir runs; fixed argument lists, never built from user input. */
public enum ComposeCommand {
    UP("up", "-d", "--remove-orphans"),
    DOWN("down"),
    PULL("pull"),
    CONFIG("config", "--quiet");

    private final List<String> args;

    ComposeCommand(String... args) {
        this.args = List.of(args);
    }

    List<String> args() {
        return args;
    }
}
