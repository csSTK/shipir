package dev.bethge.shipir.architecture.fixture.cycle.alpha;

import dev.bethge.shipir.architecture.fixture.cycle.beta.BetaThing;

/** Deliberate violation: alpha/ and beta/ depend on each other. */
public class AlphaThing {
    BetaThing beta;
}
