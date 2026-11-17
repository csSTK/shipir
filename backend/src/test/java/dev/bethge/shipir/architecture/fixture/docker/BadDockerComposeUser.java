package dev.bethge.shipir.architecture.fixture.docker;

import dev.bethge.shipir.architecture.fixture.compose.ComposeThing;

/** Deliberate violation: docker/ depends on compose/, which may only use docker/ (one-way). */
public class BadDockerComposeUser {
    ComposeThing compose;
}
