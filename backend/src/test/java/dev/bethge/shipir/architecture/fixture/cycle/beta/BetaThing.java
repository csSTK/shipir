package dev.bethge.shipir.architecture.fixture.cycle.beta;

import dev.bethge.shipir.architecture.fixture.cycle.alpha.AlphaThing;

/** Deliberate violation: beta/ depends back on alpha/. */
public class BetaThing {
    AlphaThing alpha;
}
