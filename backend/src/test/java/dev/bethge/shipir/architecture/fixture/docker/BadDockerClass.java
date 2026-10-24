package dev.bethge.shipir.architecture.fixture.docker;

import dev.bethge.shipir.architecture.fixture.host.HostThing;

/** Deliberate violation: docker/ depends on a feature. */
public class BadDockerClass {
    HostThing host;
}
