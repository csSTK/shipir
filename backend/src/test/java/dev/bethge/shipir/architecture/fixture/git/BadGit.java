package dev.bethge.shipir.architecture.fixture.git;

import dev.bethge.shipir.architecture.fixture.compose.ComposeThing;

/** Deliberate violation: git/ depends on compose/. */
public class BadGit {
    ComposeThing compose;
}
