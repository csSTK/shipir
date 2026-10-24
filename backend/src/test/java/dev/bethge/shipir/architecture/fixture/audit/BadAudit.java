package dev.bethge.shipir.architecture.fixture.audit;

import dev.bethge.shipir.architecture.fixture.ai.AiThing;

/** Deliberate violation: audit/ depends on ai/. */
public class BadAudit {
    AiThing ai;
}
