package dev.bethge.shipir.compose.execution;

import java.util.List;

/** The compose subcommands Shipir runs; fixed argument lists, never built from user input. */
public enum ComposeCommand {
    UP("up", "-d", "--remove-orphans"),
    DOWN("down"),
    DOWN_VOLUMES("down", "-v"),
    PULL("pull"),
    CONFIG("config", "--quiet"),
    /** Fully resolved configuration as JSON, for the ADMIN-only feature check (anchors, extends and include are expanded). */
    CONFIG_JSON("config", "--format", "json");

    private final List<String> args;

    ComposeCommand(String... args) {
        this.args = List.of(args);
    }

    List<String> args() {
        return args;
    }
}
