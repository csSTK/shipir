package dev.bethge.shipir.proxy;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One public hostname (plus aliases) routed to a container port. Input must pass {@link ProxyRouteValidator} first. */
@Entity
@Table(name = "proxy_routes")
public class ProxyRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(nullable = false, length = 253)
    private String hostname;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> aliases = new ArrayList<>();

    @Column(name = "container_name", nullable = false, length = 255)
    private String containerName;

    @Column(name = "target_port", nullable = false)
    private int targetPort;

    @Convert(converter = BackendSchemeConverter.class)
    @Column(name = "backend_scheme", nullable = false, length = 10)
    private BackendScheme backendScheme;

    @Column(name = "path_prefix", length = 255)
    private String pathPrefix;

    @Column(length = 50)
    private String entrypoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "cert_mode", nullable = false, length = 20)
    private CertMode certMode;

    @Column(name = "certificate_id")
    private UUID certificateId;

    @Column(name = "redirect_https", nullable = false)
    private boolean redirectHttps = true;

    /** username -> bcrypt hash; never a plaintext password. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "basic_auth_users", columnDefinition = "jsonb")
    private Map<String, String> basicAuthUsers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_headers", columnDefinition = "jsonb")
    private Map<String, String> responseHeaders;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProxyRoute() {
    }

    public static ProxyRoute create(UUID hostId, String hostname, List<String> aliases, String containerName,
                                    int targetPort, BackendScheme backendScheme, String pathPrefix, String entrypoint,
                                    CertMode certMode, UUID certificateId, boolean redirectHttps,
                                    Map<String, String> basicAuthUsers, Map<String, String> responseHeaders) {
        ProxyRoute r = new ProxyRoute();
        r.hostId = hostId;
        r.createdAt = Instant.now();
        r.apply(hostname, aliases, containerName, targetPort, backendScheme, pathPrefix, entrypoint, certMode,
                certificateId, redirectHttps, basicAuthUsers, responseHeaders);
        return r;
    }

    public void apply(String hostname, List<String> aliases, String containerName, int targetPort,
                      BackendScheme backendScheme, String pathPrefix, String entrypoint, CertMode certMode,
                      UUID certificateId, boolean redirectHttps, Map<String, String> basicAuthUsers,
                      Map<String, String> responseHeaders) {
        this.hostname = hostname;
        this.aliases = aliases == null ? new ArrayList<>() : new ArrayList<>(aliases);
        this.containerName = containerName;
        this.targetPort = targetPort;
        this.backendScheme = backendScheme;
        this.pathPrefix = pathPrefix;
        this.entrypoint = entrypoint;
        this.certMode = certMode;
        this.certificateId = certificateId;
        this.redirectHttps = redirectHttps;
        this.basicAuthUsers = basicAuthUsers == null || basicAuthUsers.isEmpty() ? null : Map.copyOf(basicAuthUsers);
        this.responseHeaders = responseHeaders == null || responseHeaders.isEmpty() ? null : Map.copyOf(responseHeaders);
        this.updatedAt = Instant.now();
    }

    /** Hostname followed by all aliases: every name this route answers to. */
    public List<String> allNames() {
        List<String> names = new ArrayList<>();
        names.add(hostname);
        names.addAll(aliases);
        return names;
    }

    public UUID getId() { return id; }
    public UUID getHostId() { return hostId; }
    public String getHostname() { return hostname; }
    public List<String> getAliases() { return aliases; }
    public String getContainerName() { return containerName; }
    public int getTargetPort() { return targetPort; }
    public BackendScheme getBackendScheme() { return backendScheme; }
    public String getPathPrefix() { return pathPrefix; }
    public String getEntrypoint() { return entrypoint; }
    public CertMode getCertMode() { return certMode; }
    public UUID getCertificateId() { return certificateId; }
    public boolean isRedirectHttps() { return redirectHttps; }
    public Map<String, String> getBasicAuthUsers() { return basicAuthUsers; }
    public Map<String, String> getResponseHeaders() { return responseHeaders; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "ProxyRoute[id=" + id + ", hostId=" + hostId + ", hostname=" + hostname + "]";
    }
}
