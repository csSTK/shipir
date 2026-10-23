package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.EnvironmentPresence;
import org.springframework.stereotype.Component;

/** Tells the first-start setup gate whether at least one environment exists. */
@Component
class HostEnvironmentPresence implements EnvironmentPresence {

    private final DockerHostRepository hosts;

    HostEnvironmentPresence(DockerHostRepository hosts) {
        this.hosts = hosts;
    }

    @Override
    public boolean hasAnyHost() {
        return hosts.count() > 0;
    }
}
