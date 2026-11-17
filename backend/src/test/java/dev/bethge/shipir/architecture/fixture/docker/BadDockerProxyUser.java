package dev.bethge.shipir.architecture.fixture.docker;

import dev.bethge.shipir.architecture.fixture.proxy.ProxyThing;

/** Deliberate violation: docker/ depends on proxy/, a module that is not listed by name anywhere. */
public class BadDockerProxyUser {
    ProxyThing proxy;
}
