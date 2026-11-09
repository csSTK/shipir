package dev.bethge.shipir.architecture.fixture.host;

import dev.bethge.shipir.architecture.fixture.docker.DockerHostConnectionManager;

import java.util.function.BiFunction;

/** Uses the pinned constructor, as a call and as a reference: must not be flagged. */
public class GoodPinnedDockerUser {
    Object manager = new DockerHostConnectionManager("props", new Object());
    BiFunction<String, Object, Object> pinnedFactory = DockerHostConnectionManager::new;
}
