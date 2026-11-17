package dev.bethge.shipir.architecture.fixture.cycle.common.config;

import dev.bethge.shipir.architecture.fixture.cycle.beta.BetaThing;

/** Composition root: depends on beta/ while beta/ must not be reported as a cycle through common.config. */
public class WiringConfig {
    BetaThing beta;
}
