package dev.bethge.shipir.host.dto;

import java.util.List;

/**
 * Host ports published by running or created containers, plus one suggestion per requested preferred port.
 * {@code free} is null only when no port is left at all.
 */
public record HostPortsResponse(List<UsedPort> used, List<Suggestion> suggestions) {

    public record UsedPort(int port, String protocol) {
    }

    public record Suggestion(int preferred, Integer free) {
    }
}
