package dev.bethge.shipir.proxy;

import dev.bethge.shipir.compose.generator.ManagedProxyLookup;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Tells the Compose generator which proxy is managed on a host (visibility checked like the other proxy reads). */
@Component
class ManagedProxyTypeLookup implements ManagedProxyLookup {

    private final ProxyHostAccess access;
    private final ProxyInstanceRepository instances;

    ManagedProxyTypeLookup(ProxyHostAccess access, ProxyInstanceRepository instances) {
        this.access = access;
        this.instances = instances;
    }

    @Override
    public Optional<ProxyType> managedProxyType(UUID hostId) {
        try {
            access.requireView(hostId);
        } catch (ProxyException e) {
            throw new HostNotFoundException();
        }
        return instances.findByHostId(hostId).map(ProxyInstance::getType);
    }
}
