package dev.bethge.shipir.host;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.net.EgressDeniedException;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.security.HostRemovedEvent;
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
import dev.bethge.shipir.host.dto.SetHostTagsRequest;
import dev.bethge.shipir.permission.CurrentAccess;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HostService {

    private static final String LOCAL_INDEX = "docker_hosts_single_local_idx";

    private final DockerHostRepository hosts;
    private final UserRepository users;
    private final SecretCipher cipher;
    private final DockerSystemService docker;
    private final DockerHostConnectionManager connections;
    private final CurrentAccess access;
    private final EgressPolicy egress;
    private final HostTagService tags;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    HostService(DockerHostRepository hosts, UserRepository users, SecretCipher cipher,
                DockerSystemService docker, DockerHostConnectionManager connections, CurrentAccess access,
                EgressPolicy egress, HostTagService tags, ApplicationEventPublisher events,
                PlatformTransactionManager transactions) {
        this.hosts = hosts;
        this.users = users;
        this.cipher = cipher;
        this.docker = docker;
        this.connections = connections;
        this.access = access;
        this.egress = egress;
        this.tags = tags;
        this.events = events;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Hosts the signed-in user may see (all for ADMIN and system callers). */
    @Transactional(readOnly = true)
    public List<HostResponse> list() {
        var visible = access.visibleHostIds();
        List<DockerHost> selected = hosts.findAll().stream()
                .filter(h -> visible.isEmpty() || visible.get().contains(h.getId()))
                .sorted(Comparator.comparing(DockerHost::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Map<UUID, List<String>> tagsByHost = tags.tagsByHost(selected.stream().map(DockerHost::getId).toList());
        return selected.stream()
                .map(h -> toResponse(h, tagsByHost.getOrDefault(h.getId(), List.of())))
                .toList();
    }

    /** Connection test without storing anything; connection problems are reported in the result. */
    ConnectionTestResult testConnection(ConnectionTestRequest request) {
        DockerEndpoint endpoint = toEndpoint(request.connectionType(), request.host(), request.port(),
                request.tlsCaPem(), request.tlsCertPem(), request.tlsKeyPem());
        return ping(endpoint);
    }

    /**
     * Ping for a connection test or create. A TCP target is checked against the egress policy first (the daemon is never
     * contacted when denied), and for TCP every failure (denied, refused, timed out, TLS, permission) reports the same
     * generic UNREACHABLE, so the test cannot be used to map internal ports. A LOCAL socket keeps the real error code
     * (no port to probe). docker-java resolves the host itself, so a DNS rebind after the check is not excluded.
     */
    private ConnectionTestResult ping(DockerEndpoint endpoint) {
        if (endpoint instanceof DockerEndpoint.Tcp tcp) {
            try {
                egress.check(tcp.host());
            } catch (EgressDeniedException e) {
                return unreachable();
            }
        }
        ConnectionTestResult result = docker.ping(endpoint);
        return result.ok() || !(endpoint instanceof DockerEndpoint.Tcp) ? result : unreachable();
    }

    private static ConnectionTestResult unreachable() {
        return new ConnectionTestResult(false, null, DockerErrorCode.UNREACHABLE, "The Docker daemon is not reachable.");
    }

    /** Not transactional: the (slow) connection test must not hold a database connection. */
    @Auditable(action = "HOST_CREATE", targetType = "HOST", targetId = "#result?.id",
            details = "{'tags': #result.tags()}")
    public HostResponse create(CreateHostRequest request, String username) {
        List<String> requestedTags = HostTagPolicy.normalize(request.tags());
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
            throw localExists();
        }
        ConnectionTestResult result = ping(endpoint);
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
            // host and tags commit together; a lost race on the name or the LOCAL slot rolls both back
            return tx.execute(status -> {
                DockerHost saved = hosts.saveAndFlush(host);
                tags.replace(saved.getId(), requestedTags);
                return toResponse(saved, requestedTags);
            });
        } catch (DataIntegrityViolationException e) {
            // lost a race against another request with the same name or the single LOCAL environment
            throw isLocalIndexViolation(e) ? localExists() : nameTaken();
        }
    }

    /** Rename only; the tags stay as they are. */
    @Auditable(action = "HOST_RENAME", targetType = "HOST", targetId = "#id", details = "{'tags': #result.tags()}")
    @Transactional
    public HostResponse rename(UUID id, String name) {
        return doRename(id, name, null);
    }

    /** Renames and, when {@code requestedTags} is not null, replaces the tags. Invalid tags change nothing. */
    @Auditable(action = "HOST_RENAME", targetType = "HOST", targetId = "#id", details = "{'tags': #result.tags()}")
    @Transactional
    public HostResponse rename(UUID id, String name, List<String> requestedTags) {
        return doRename(id, name, requestedTags);
    }

    // both overloads are audited and transactional; each is called through the proxy from outside the service
    private HostResponse doRename(UUID id, String name, List<String> requestedTags) {
        DockerHost host = find(id);
        List<String> normalizedTags = requestedTags == null ? null : HostTagPolicy.normalize(requestedTags);
        if (!host.getName().equals(name) && hosts.existsByName(name)) {
            throw nameTaken();
        }
        host.rename(name);
        try {
            hosts.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
        if (normalizedTags != null) {
            replaceTags(id, normalizedTags);
        }
        connections.evict(id);
        return toResponse(host, tags.tagsOf(id));
    }

    /** Replaces the whole tag set of one environment; an empty list removes all tags. */
    @Auditable(action = "HOST_UPDATE", targetType = "HOST", targetId = "#id", details = "{'tags': #result.tags()}")
    @Transactional
    public HostResponse setTags(UUID id, SetHostTagsRequest request) {
        DockerHost host = find(id);
        replaceTags(id, HostTagPolicy.normalize(request.tags()));
        return toResponse(host, tags.tagsOf(id));
    }

    private void replaceTags(UUID id, List<String> normalizedTags) {
        try {
            tags.replace(id, normalizedTags);
        } catch (DataIntegrityViolationException e) {
            // another request changed the same tags between the read and the insert
            throw new HostException(HttpStatus.CONFLICT, "TAGS_CONFLICT", "The tags changed concurrently, retry");
        }
    }

    @Auditable(action = "HOST_DELETE", targetType = "HOST", targetId = "#id")
    @Transactional
    public void delete(UUID id) {
        hosts.delete(find(id));
        hosts.flush();
        connections.evict(id);
        events.publishEvent(new HostRemovedEvent(id));
    }

    private DockerHost find(UUID id) {
        return hosts.findById(id).orElseThrow(
                () -> new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
    }

    private static HostException localExists() {
        return new HostException(HttpStatus.CONFLICT, "LOCAL_HOST_EXISTS", "A local environment already exists");
    }

    private static boolean isLocalIndexViolation(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(LOCAL_INDEX)) {
                return true;
            }
        }
        return false;
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

    private static HostResponse toResponse(DockerHost h, List<String> tags) {
        return new HostResponse(h.getId(), h.getName(), h.getConnectionType(), h.getHost(), h.getPort(),
                h.hasTls(), h.getCreatedAt(), tags);
    }
}
