package dev.bethge.shipir.docker;

import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One cached docker-java client per host id. The {@code client}/{@code createClient} methods hand out
 * docker-java types and are for docker/ internals only (e.g. {@code docker.client}); other features
 * use the services and {@link #evict(UUID)}.
 */
@Component
@EnableConfigurationProperties(DockerProperties.class)
public class DockerHostConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DockerHostConnectionManager.class);

    private final Map<UUID, DockerClient> clients = new ConcurrentHashMap<>();
    private final DockerClientFactory factory;
    private final DockerProperties properties;

    /** Production wiring: TCP host names are resolved once, through the egress policy, and connected as validated. */
    @Autowired
    public DockerHostConnectionManager(DockerProperties properties, EgressPolicy egress) {
        this(new DefaultDockerClientFactory(properties, egress.dnsResolver()), properties);
    }

    /** System DNS, no egress policy: for tests against local daemons only. */
    public DockerHostConnectionManager(DockerProperties properties) {
        this(new DefaultDockerClientFactory(properties, SystemDefaultDnsResolver.INSTANCE), properties);
    }

    DockerHostConnectionManager(DockerClientFactory factory, DockerProperties properties) {
        this.factory = factory;
        this.properties = properties;
    }

    /** The Docker daemon of the machine Shipir runs on. */
    public DockerEndpoint localEndpoint() {
        return new DockerEndpoint.Local(properties.localSocket());
    }

    /**
     * Upper bound for one Docker call. The transport only honors it on TCP; Unix sockets ignore the socket
     * timeout, so callers enforce it themselves.
     */
    public Duration readTimeout() {
        return properties.readTimeout();
    }

    /** Cached client for {@code hostId}, created from {@code endpoint} on first use. */
    public DockerClient client(UUID hostId, DockerEndpoint endpoint) {
        return clients.computeIfAbsent(hostId, id -> factory.create(endpoint));
    }

    /** A new, uncached client; the caller closes it. */
    public DockerClient createClient(DockerEndpoint endpoint) {
        return factory.create(endpoint);
    }

    /** Closes and forgets the client of {@code hostId}, e.g. after its endpoint changed or it was deleted. */
    public void evict(UUID hostId) {
        DockerClient client = clients.remove(hostId);
        if (client != null) {
            closeQuietly(client);
        }
    }

    @PreDestroy
    void closeAll() {
        clients.keySet().forEach(this::evict);
    }

    private static void closeQuietly(DockerClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("Closing Docker client failed: {}", e.toString());
        }
    }
}
