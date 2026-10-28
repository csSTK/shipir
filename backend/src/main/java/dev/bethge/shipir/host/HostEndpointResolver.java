package dev.bethge.shipir.host;

import dev.bethge.shipir.common.net.EgressDeniedException;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link DockerEndpoint} of a stored host, decrypting its TLS material in memory only. A TCP host is checked
 * against the egress policy on every call (the stored address may have been fine at creation and denied since, or its
 * DNS name may now point inside); a denied host fails like an unreachable one, so no daemon is contacted.
 * docker-java resolves the name again when it connects, so a rebind between check and connect is not excluded.
 */
@Component
public class HostEndpointResolver {

    private final DockerHostConnectionManager connections;
    private final SecretCipher cipher;
    private final EgressPolicy egress;

    HostEndpointResolver(DockerHostConnectionManager connections, SecretCipher cipher, EgressPolicy egress) {
        this.connections = connections;
        this.cipher = cipher;
        this.egress = egress;
    }

    public DockerEndpoint endpointOf(DockerHost host) {
        if (host.getConnectionType() == ConnectionType.LOCAL) {
            return connections.localEndpoint();
        }
        try {
            egress.check(host.getHost());
        } catch (EgressDeniedException e) {
            throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "The Docker daemon is not reachable.", e);
        }
        var tls = new DockerEndpoint.TlsMaterial(
                cipher.decrypt(host.getTlsCaEncrypted()),
                cipher.decrypt(host.getTlsCertEncrypted()),
                cipher.decrypt(host.getTlsKeyEncrypted()));
        return new DockerEndpoint.Tcp(host.getHost(), host.getPort(), tls);
    }
}
