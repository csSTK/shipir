package dev.bethge.shipir.architecture.fixture.docker;

import dev.bethge.shipir.architecture.fixture.common.CommonThing;

/** Allowed: docker/ may depend on common/. */
public class GoodDockerCommonUser {
    CommonThing common;
}
