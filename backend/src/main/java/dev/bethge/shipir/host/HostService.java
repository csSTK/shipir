package dev.bethge.shipir.host;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerEndpoint.TlsMaterial;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.docker.tls.TlsMaterialValidator;
import dev.bethge.shipir.host.dto.ConnectionTestRequest;
import dev.bethge.shipir.host.dto.CreateHostRequest;
import dev.bethge.shipir.host.dto.HostResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class HostService {

    private final DockerHostRepository hosts;
    private final UserRepository users;
    private final SecretCipher cipher;
    private final DockerSystemService docker;
    private final DockerHostConnectionManager connections;

    HostService(DockerHostRepository hosts, UserRepository users, SecretCipher cipher,
                DockerSystemService docker, DockerHostConnectionManager connections) {
        this.hosts = hosts;
        this.users = users;
        this.cipher = cipher;
        this.docker = docker;
        this.connections = connections;
    }

    @Transactional(readOnly = true)
    public List<HostResponse> list() {
        return hosts.findAll().stream()
                .sorted(Comparator.comparing(DockerHost::getName, String.CASE_INSENSITIVE_ORDER))
                .map(HostService::toResponse)
                .toList();
    }

    /** Connection test without storing anything; connection problems are reported in the result. */
    ConnectionTestResult testConnection(ConnectionTestRequest request) {
        DockerEndpoint endpoint = toEndpoint(request.connectionType(), request.host(), request.port(),
                request.tlsCaPem(), request.tlsCertPem(), request.tlsKeyPem());
        return docker.ping(endpoint);
    }

    /** Not transactional: the (slow) connection test must not hold a database connection. */
    @Auditable(action = "HOST_CREATE", targetType = "HOST", targetId = "#result?.id")
    public HostResponse create(CreateHostRequest request, String username) {
        DockerEndpoint endpoint = toEndpoint(request.connectionType(), request.host(), request.port(),
                request.tlsCaPem(), request.tlsCertPem(), request.tlsKeyPem());
        if (request.connectionType() == ConnectionType.TCP && !cipher.isConfigured()) {
            throw new HostException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                    "TCP environments need shipir.encryption-key (SHIPIR_ENCRYPTION_KEY) to store certificates");
        }
        if (hosts.existsByName(request.name())) {
            throw nameTaken();
        }
        if (request.connectionType() == ConnectionType.LOCAL && hosts.existsByConnectionType(ConnectionType.LOCAL)) {
            throw new HostException(HttpStatus.CONFLICT, "LOCAL_HOST_EXISTS",
                    "A local environment already exists");
        }
        ConnectionTestResult result = docker.ping(endpoint);
        if (!result.ok()) {
            throw new HostException(HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTION_FAILED",
                    "Could not connect to the Docker daemon",
                    List.of(String.valueOf(result.errorCode()), String.valueOf(result.message())));
        }
        UUID ownerId = users.findByUsername(username).orElseThrow().getId();
        DockerHost host = request.connectionType() == ConnectionType.LOCAL
                ? DockerHost.local(request.name(), ownerId)
                : DockerHost.tcp(request.name(), request.host(), request.port(),
                        cipher.encrypt(request.tlsCaPem()), cipher.encrypt(request.tlsCertPem()),
                        cipher.encrypt(request.tlsKeyPem()), ownerId);
        try {
            return toResponse(hosts.saveAndFlush(host));
        } catch (DataIntegrityViolationException e) {
            // lost a race against another request with the same name
            throw nameTaken();
        }
    }

    @Auditable(action = "HOST_RENAME", targetType = "HOST", targetId = "#id")
    @Transactional
    public HostResponse rename(UUID id, String name) {
        DockerHost host = find(id);
        if (!host.getName().equals(name) && hosts.existsByName(name)) {
            throw nameTaken();
        }
        host.rename(name);
        try {
            hosts.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
        connections.evict(id);
        return toResponse(host);
    }

    @Auditable(action = "HOST_DELETE", targetType = "HOST", targetId = "#id")
    @Transactional
    public void delete(UUID id) {
        hosts.delete(find(id));
        hosts.flush();
        connections.evict(id);
    }

    private DockerHost find(UUID id) {
        return hosts.findById(id).orElseThrow(
                () -> new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
    }

    private static HostException nameTaken() {
        return new HostException(HttpStatus.CONFLICT, "HOST_NAME_TAKEN", "An environment with this name already exists");
    }

    /** Checks the cross-field rules, validates PEM without touching the network, and builds the endpoint. */
    private DockerEndpoint toEndpoint(ConnectionType type, String host, Integer port, String ca, String cert,
                                      String key) {
        List<String> problems = new ArrayList<>();
        if (type == ConnectionType.LOCAL) {
            rejectPresent(problems, "host", host);
            rejectPresent(problems, "port", port);
            rejectPresent(problems, "tlsCaPem", ca);
            rejectPresent(problems, "tlsCertPem", cert);
            rejectPresent(problems, "tlsKeyPem", key);
        } else {
            requirePresent(problems, "host", host);
            requirePresent(problems, "port", port);
            requirePresent(problems, "tlsCaPem", ca);
            requirePresent(problems, "tlsCertPem", cert);
            requirePresent(problems, "tlsKeyPem", key);
        }
        if (!problems.isEmpty()) {
            problems.sort(null);
            throw new HostException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", problems);
        }
        if (type == ConnectionType.LOCAL) {
            return connections.localEndpoint();
        }
        TlsMaterial tls = new TlsMaterial(ca, cert, key);
        try {
            TlsMaterialValidator.validate(tls);
        } catch (DockerConnectionException e) {
            if (e.getCode() == DockerErrorCode.INVALID_TLS_MATERIAL) {
                throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_TLS_MATERIAL",
                        "The CA, client certificate or client key is not usable PEM");
            }
            throw e;
        }
        return new DockerEndpoint.Tcp(host.trim(), port, tls);
    }

    private static void requirePresent(List<String> problems, String field, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            problems.add(field + ": required for TCP environments");
        }
    }

    private static void rejectPresent(List<String> problems, String field, Object value) {
        if (value != null) {
            problems.add(field + ": not allowed for LOCAL environments");
        }
    }

    private static HostResponse toResponse(DockerHost h) {
        return new HostResponse(h.getId(), h.getName(), h.getConnectionType(), h.getHost(), h.getPort(),
                h.hasTls(), h.getCreatedAt());
    }
}
