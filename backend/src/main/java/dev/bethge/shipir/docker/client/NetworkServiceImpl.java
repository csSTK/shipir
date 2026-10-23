package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Network;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.NetworkDto;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NetworkServiceImpl implements NetworkService {

    private final DockerCallSupport calls;

    public NetworkServiceImpl(DockerHostConnectionManager manager) {
        this.calls = new DockerCallSupport(manager);
    }

    @Override
    public List<NetworkDto> list(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, client -> {
            Map<String, Integer> attached = new HashMap<>();
            for (Container c : client.listContainersCmd().withShowAll(true).exec()) {
                if (c.getNetworkSettings() == null || c.getNetworkSettings().getNetworks() == null) continue;
                c.getNetworkSettings().getNetworks().keySet().forEach(name -> attached.merge(name, 1, Integer::sum));
            }
            return client.listNetworksCmd().exec().stream()
                    .map(n -> toDto(n, attached.getOrDefault(n.getName(), 0)))
                    .toList();
        });
    }

    private static NetworkDto toDto(Network n, int containerCount) {
        return new NetworkDto(n.getId(), n.getName(), n.getDriver(), n.getScope(), subnet(n),
                Boolean.TRUE.equals(n.getInternal()), containerCount);
    }

    private static String subnet(Network n) {
        if (n.getIpam() == null || n.getIpam().getConfig() == null) return null;
        return n.getIpam().getConfig().stream()
                .map(Network.Ipam.Config::getSubnet)
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse(null);
    }
}
