package dev.bethge.shipir.architecture.fixture.host;

import dev.bethge.shipir.architecture.fixture.docker.DockerHostConnectionManager;

import java.util.function.Function;

/** Deliberate violation: takes the unpinned (system DNS) constructor as a method reference, which a call-only rule misses. */
public class BadUnpinnedConstructorReferenceUser {
    Function<String, Object> factory = DockerHostConnectionManager::new;
}
