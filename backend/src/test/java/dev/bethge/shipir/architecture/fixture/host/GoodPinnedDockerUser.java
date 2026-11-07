package dev.bethge.shipir.architecture.fixture.host;

import dev.bethge.shipir.architecture.fixture.docker.DockerHostConnectionManager;

/** Uses the pinned constructor: must not be flagged. */
public class GoodPinnedDockerUser {
    Object manager = new DockerHostConnectionManager("props", new Object());
}
