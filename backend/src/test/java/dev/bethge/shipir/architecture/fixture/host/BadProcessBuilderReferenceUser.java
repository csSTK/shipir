package dev.bethge.shipir.architecture.fixture.host;

import java.util.function.Function;

/** Deliberate violation: takes the ProcessBuilder constructor as a method reference, which a call-only rule misses. */
public class BadProcessBuilderReferenceUser {
    Function<String, ProcessBuilder> factory = ProcessBuilder::new;
}
