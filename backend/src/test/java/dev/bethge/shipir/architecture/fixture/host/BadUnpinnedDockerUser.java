package dev.bethge.shipir.architecture.fixture.host;

import dev.bethge.shipir.architecture.fixture.docker.DockerHostConnectionManager;

/** Deliberate violation: builds a connection manager through the unpinned (system DNS) constructor. */
public class BadUnpinnedDockerUser {
    Object manager = new DockerHostConnectionManager("props");
}
