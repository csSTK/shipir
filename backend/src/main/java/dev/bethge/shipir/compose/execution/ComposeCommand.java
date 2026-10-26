package dev.bethge.shipir.compose.execution;

import java.util.List;

/** The compose subcommands Shipir runs; fixed argument lists, never built from user input. */
public enum ComposeCommand {
    UP("up", "-d", "--remove-orphans"),
    DOWN("down"),
    DOWN_VOLUMES("down", "-v"),
    /** Up for single services: no {@code --remove-orphans}, which would remove every service not named. */
    UP_SERVICE("up", "-d"),
    PULL("pull"),
    RESTART("restart"),
    CONFIG("config", "--quiet"),
    /** Fully resolved configuration as JSON, for the ADMIN-only feature check (anchors, extends and include are expanded). */
    CONFIG_JSON("config", "--format", "json"),
    /** Service names, one per line; with {@code --profile *} also those behind a profile. */
    CONFIG_SERVICES("config", "--services"),
    /** Profile names used in the file, one per line. */
    CONFIG_PROFILES("config", "--profiles");

    private final List<String> args;

    ComposeCommand(String... args) {
        this.args = List.of(args);
    }

    List<String> args() {
        return args;
    }
}
