package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import org.springframework.stereotype.Component;

/** Builds the {@link DockerEndpoint} of a stored host, decrypting its TLS material in memory only. */
@Component
public class HostEndpointResolver {

    private final DockerHostConnectionManager connections;
    private final SecretCipher cipher;

    HostEndpointResolver(DockerHostConnectionManager connections, SecretCipher cipher) {
        this.connections = connections;
        this.cipher = cipher;
    }

    public DockerEndpoint endpointOf(DockerHost host) {
        if (host.getConnectionType() == ConnectionType.LOCAL) {
            return connections.localEndpoint();
        }
        var tls = new DockerEndpoint.TlsMaterial(
                cipher.decrypt(host.getTlsCaEncrypted()),
                cipher.decrypt(host.getTlsCertEncrypted()),
                cipher.decrypt(host.getTlsKeyEncrypted()));
        return new DockerEndpoint.Tcp(host.getHost(), host.getPort(), tls);
    }
}
