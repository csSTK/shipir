package dev.bethge.shipir.architecture.fixture.cycle.beta;

import dev.bethge.shipir.architecture.fixture.cycle.common.config.WiringConfig;

/** Closes a loop beta -> common.config -> beta that the rule must ignore (composition root). */
public class BetaConfigUser {
    WiringConfig wiring;
}
