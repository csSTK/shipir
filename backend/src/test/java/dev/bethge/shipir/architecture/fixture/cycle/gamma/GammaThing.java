package dev.bethge.shipir.architecture.fixture.cycle.gamma;

import dev.bethge.shipir.architecture.fixture.cycle.delta.DeltaThing;

/** Deliberate violation: gamma/ and delta/ depend on each other (the "new" cycle next to alpha/beta). */
public class GammaThing {
    DeltaThing delta;
}
