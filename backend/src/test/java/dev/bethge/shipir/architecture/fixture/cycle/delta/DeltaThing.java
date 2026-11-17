package dev.bethge.shipir.architecture.fixture.cycle.delta;

import dev.bethge.shipir.architecture.fixture.cycle.gamma.GammaThing;

/** Deliberate violation: delta/ depends back on gamma/. */
public class DeltaThing {
    GammaThing gamma;
}
