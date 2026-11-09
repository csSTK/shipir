package dev.bethge.shipir.proxy.status;

import dev.bethge.shipir.common.security.HostRemovedEvent;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.proxy.CertMode;
import dev.bethge.shipir.proxy.ProxyCertificate;
import dev.bethge.shipir.proxy.ProxyCertificateRepository;
import dev.bethge.shipir.proxy.ProxyHostAccess;
import dev.bethge.shipir.proxy.ProxyInstance;
import dev.bethge.shipir.proxy.ProxyInstanceRepository;
import dev.bethge.shipir.proxy.ProxyRoute;
import dev.bethge.shipir.proxy.ProxyRouteRepository;
import dev.bethge.shipir.proxy.ProxyRouteService;
import dev.bethge.shipir.proxy.ProxySyncService;
import dev.bethge.shipir.proxy.dto.ProxyRouteView;
import dev.bethge.shipir.proxy.status.dto.CertificateStatus;
import dev.bethge.shipir.proxy.status.dto.CertificateStatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Read-only certificate status per published route. The routes come from {@link ProxyRouteService#list}, so a user with
 * only deployment grants never learns anything about routes of containers they cannot see. What is read from the proxy
 * (acme.json or Caddy storage, recent log lines) is host-wide and cached for a short time; private keys never leave
 * {@link AcmeJsonParser}. A proxy that cannot be read leaves ACME routes PENDING instead of failing the request.
 */
@Service
public class ProxyStatusService {

    private static final Logger log = LoggerFactory.getLogger(ProxyStatusService.class);
    static final String ACME_HTTP_FILE = "/letsencrypt/acme-http.json";
    static final String ACME_DNS_FILE = "/letsencrypt/acme-dns.json";
    /** Last good facts are served during an outage for at most this many cache TTLs. */
    static final int STALE_FACTOR = 10;
    static final int LOG_TAIL = 500;
    static final int MAX_LOG_LINE = 2000;

    private final ProxyInstanceRepository instances;
    private final ProxyCertificateRepository certificates;
    private final ProxyRouteService routes;
    private final ProxyRouteRepository routeRepository;
    private final ProxyHostAccess access;
    private final ProxySyncService sync;
    private final ContainerFileService files;
    private final ContainerLogService logs;
    private final CaddyStorageReader caddy;
    private final Clock clock;
    private final StatusCache cache;
    private final Duration logTimeout;

    @Autowired
    ProxyStatusService(ProxyInstanceRepository instances, ProxyCertificateRepository certificates,
                       ProxyRouteService routes, ProxyRouteRepository routeRepository, ProxyHostAccess access, ProxySyncService sync,
                       ContainerFileService files, ContainerLogService logs, Clock clock,
                       @Value("${shipir.proxy.status.log-timeout:5s}") Duration logTimeout,
                       @Value("${shipir.proxy.status.cache-ttl:30s}") Duration cacheTtl,
                       @Value("${shipir.proxy.status.refresh-floor:5s}") Duration refreshFloor) {
        this.instances = instances;
        this.certificates = certificates;
        this.routes = routes;
        this.routeRepository = routeRepository;
        this.access = access;
        this.sync = sync;
        this.files = files;
        this.logs = logs;
        this.caddy = new CaddyStorageReader(files);
        this.clock = clock;
        this.cache = new StatusCache(clock, cacheTtl, refreshFloor, cacheTtl.multipliedBy(STALE_FACTOR));
        this.logTimeout = logTimeout;
    }

    /** A deleted host's cached proxy status goes with it. Runs after the deleting transaction commits. */
    @TransactionalEventListener(fallbackExecution = true)
    void onHostRemoved(HostRemovedEvent event) {
        cache.evict(event.hostId());
    }

    /** Status of every route the current user may see; {@code refresh} asks for fresh data (rate limited). */
    public List<CertificateStatusView> status(UUID hostId, boolean refresh) {
        access.requireView(hostId);
        ProxyInstance instance = instances.findByHostId(hostId).orElse(null);
        if (instance == null) {
            return List.of();
        }
        List<ProxyRouteView> visible = routes.list(hostId);
        if (visible.isEmpty()) {
            return List.of();
        }
        ProxyFacts facts = facts(hostId, instance.getType(), refresh);
        Map<UUID, Instant> custom = new HashMap<>();
        for (ProxyCertificate c : certificates.findByHostId(hostId)) {
            custom.put(c.getId(), c.getNotAfter());
        }
        Instant now = clock.instant();
        return visible.stream()
                .sorted(Comparator.comparing(ProxyRouteView::hostname))
                .map(r -> view(r, instance.getType(), facts, custom, now))
                .toList();
    }

    private CertificateStatusView view(ProxyRouteView route, ProxyType type, ProxyFacts facts,
                                       Map<UUID, Instant> custom, Instant now) {
        String host = route.hostname();
        if (route.certMode() == CertMode.INTERNAL) {
            return new CertificateStatusView(host, CertificateStatus.INTERNAL, null, null);
        }
        if (route.certMode() == CertMode.CUSTOM) {
            return new CertificateStatusView(host, CertificateStatus.CUSTOM, custom.get(route.certificateId()), null);
        }
        List<IssuedCert> issued = type == ProxyType.CADDY ? facts.caddy()
                : route.certMode() == CertMode.ACME_DNS ? facts.acmeDns() : facts.acmeHttp();
        IssuedCert best = CertificateMatcher.best(issued, host).orElse(null);
        if (best != null && best.notAfter().isAfter(now)) {
            return new CertificateStatusView(host, CertificateStatus.ISSUED, best.notAfter(), null);
        }
        Instant notAfter = best == null ? null : best.notAfter();
        return LogErrorExtractor.lastError(facts.logLines(), host)
                .map(err -> new CertificateStatusView(host, CertificateStatus.FAILED, notAfter, err))
                .orElseGet(() -> new CertificateStatusView(host, CertificateStatus.PENDING, notAfter, null));
    }

    private ProxyFacts facts(UUID hostId, ProxyType type, boolean refresh) {
        // never throws: a failed load serves the last good facts (or none) and is retried at most once per floor
        return cache.get(hostId, refresh, () -> load(hostId, type));
    }

    private ProxyFacts load(UUID hostId, ProxyType type) {
        DockerEndpoint endpoint = access.endpoint(hostId);
        String containerId = sync.proxyContainerId(hostId, type);
        List<IssuedCert> http = List.of();
        List<IssuedCert> dns = List.of();
        List<IssuedCert> caddyCerts = List.of();
        boolean[] degraded = {false};
        if (type == ProxyType.CADDY) {
            caddyCerts = caddy.read(hostId, endpoint, containerId);
        } else {
            // all routes of the host, not just the caller's visible ones: the facts are cached host-wide
            Set<CertMode> modes = new HashSet<>();
            for (ProxyRoute r : routeRepository.findByHostId(hostId)) {
                modes.add(r.getCertMode());
            }
            http = readAcme(hostId, endpoint, containerId, ACME_HTTP_FILE, modes.contains(CertMode.ACME_HTTP), degraded);
            dns = readAcme(hostId, endpoint, containerId, ACME_DNS_FILE, modes.contains(CertMode.ACME_DNS), degraded);
        }
        List<String> lines = recentLog(hostId, endpoint, containerId, degraded);
        return new ProxyFacts(http, dns, caddyCerts, lines, degraded[0]);
    }

    private List<IssuedCert> readAcme(UUID hostId, DockerEndpoint endpoint, String containerId, String path,
                                         boolean needed, boolean[] degraded) {
        try {
            // readFile returns the whole acme.json, private keys included, as a byte[] up to the cap; only
            // AcmeJsonParser sees it and it keeps names and end dates only
            return AcmeJsonParser.parse(files.readFile(hostId, endpoint, containerId, path, AcmeJsonParser.MAX_BYTES));
        } catch (DockerConnectionException e) {
            if (e.getCode() == DockerErrorCode.NOT_FOUND) {
                // the store is only created once a route of that mode asks for a certificate: without such a
                // route (an HTTP-only setup never has acme-dns.json) it is normal; with one, the certificate is
                // still being obtained and the facts must be re-read soon
                degraded[0] |= needed;
                return List.of();
            }
            if (e.getCode() == DockerErrorCode.SIZE_LIMIT_EXCEEDED || e.getCode() == DockerErrorCode.INVALID_PATH) {
                degraded[0] = true;
                return List.of();
            }
            throw e;
        } catch (StatusParseException e) {
            log.warn("Certificate store {} of host {} could not be read", path, hostId);
            degraded[0] = true;
            return List.of();
        }
    }

    /** Bounded: the last {@link #LOG_TAIL} lines, at most {@code logTimeout}. A log that cannot be read in full marks the facts degraded. */
    private List<String> recentLog(UUID hostId, DockerEndpoint endpoint, String containerId, boolean[] degraded) {
        List<String> lines = new ArrayList<>();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        Closeable handle = null;
        try {
            handle = logs.snapshot(hostId, endpoint, containerId, LOG_TAIL, null, false, line -> {
                synchronized (lines) {
                    if (lines.size() < LOG_TAIL) {
                        String t = line.text();
                        lines.add(t.length() > MAX_LOG_LINE ? t.substring(0, MAX_LOG_LINE) : t);
                    }
                }
            }, failure -> {
                if (failure != null) {
                    degraded[0] = true;
                }
                ended.complete(null);
            });
            ended.get(logTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException | DockerConnectionException e) {
            degraded[0] = true; // use what arrived
        } catch (InterruptedException e) {
            degraded[0] = true;
            Thread.currentThread().interrupt();
        } finally {
            if (handle != null) {
                try {
                    handle.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }
}
