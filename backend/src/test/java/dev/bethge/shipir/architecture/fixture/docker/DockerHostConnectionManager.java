package dev.bethge.shipir.architecture.fixture.docker;

/** Stand-in for the real connection manager: a one-argument (unpinned) constructor and a two-argument (pinned) one. */
public class DockerHostConnectionManager {

    public DockerHostConnectionManager(String properties) {
    }

    public DockerHostConnectionManager(String properties, Object egress) {
    }
}
