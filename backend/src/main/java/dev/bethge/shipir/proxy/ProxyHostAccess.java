package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import dev.bethge.shipir.permission.CurrentAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may see or change the proxy of a host, and how to reach its Docker daemon. Visibility first (404 when the host
 * does not exist or is not visible), then the role/permission (403), like the other host-scoped services. Writes need
 * the ADMIN role and OPERATE on the host.
 */
@Component
public class ProxyHostAccess {

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final CurrentAccess access;

    ProxyHostAccess(DockerHostRepository hosts, HostEndpointResolver endpoints, CurrentAccess access) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.access = access;
    }

    public void requireView(UUID hostId) {
        if (!hosts.existsById(hostId) || !access.canView(hostId)) {
            throw new ProxyException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found");
        }
    }

    public void requireAdminOperate(UUID hostId) {
        requireView(hostId);
        if (!access.isAdmin() || !access.canOperate(hostId)) {
            throw new ProxyException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
        }
    }

    /** Endpoint of the host; no permission check (the callers did that, or run as the system). */
    public DockerEndpoint endpoint(UUID hostId) {
        DockerHost host = hosts.findById(hostId).orElseThrow(
                () -> new ProxyException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
        try {
            return endpoints.endpointOf(host);
        } catch (DockerConnectionException e) {
            throw dockerFailure(e);
        } catch (EncryptionKeyMissingException e) {
            throw new ProxyException(HttpStatus.BAD_GATEWAY, "ENCRYPTION_KEY_MISSING", "No encryption key is configured");
        } catch (SecretDecryptionException e) {
            throw new ProxyException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED", "Stored connection secrets could not be decrypted");
        }
    }

    /** 502 with the stable Docker error code; the daemon's own text is never passed on. */
    static ProxyException dockerFailure(DockerConnectionException e) {
        return new ProxyException(HttpStatus.BAD_GATEWAY, e.getCode().name(), "Environment could not be reached");
    }
}
