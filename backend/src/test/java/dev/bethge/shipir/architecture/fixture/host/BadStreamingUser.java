package dev.bethge.shipir.architecture.fixture.host;

import dev.bethge.shipir.architecture.fixture.streaming.StreamThing;

/** Deliberate violation: host/ depends on streaming/. */
public class BadStreamingUser {
    StreamThing stream;
}
