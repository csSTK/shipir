package dev.bethge.shipir.architecture.fixture.docker;

import dev.bethge.shipir.architecture.fixture.streaming.StreamThing;

/** Deliberate violation: docker/ depends on streaming/, which may only use docker/ (one-way). */
public class BadDockerStreamingUser {
    StreamThing stream;
}
