package dev.bethge.shipir.proxy;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.common.security.SystemScope;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.DeploymentRepository;
import dev.bethge.shipir.compose.DeploymentRunService;
import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.compose.DeploymentStatus;
import dev.bethge.shipir.compose.dto.DeploymentCreateRequest;
import dev.bethge.shipir.compose.dto.DeploymentDetailResponse;
import dev.bethge.shipir.compose.dto.DeploymentRunResponse;
import dev.bethge.shipir.compose.dto.DeploymentUpdateRequest;
import dev.bethge.shipir.compose.dto.EnvEntry;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.NetworkCreateSpec;
import dev.bethge.shipir.host.HostPortsService;
import dev.bethge.shipir.host.HostResourcesService;
import dev.bethge.shipir.host.dto.HostPortsResponse;
import dev.bethge.shipir.host.dto.ProxyDetectionResponse;
import dev.bethge.shipir.common.exception.CodedException;
import dev.bethge.shipir.proxy.dto.ProxyInstanceView;
import dev.bethge.shipir.proxy.dto.ProxyManagedResponse;
import dev.bethge.shipir.proxy.dto.ProxySetupRequest;
import dev.bethge.shipir.proxy.dto.ProxySetupResponse;
import dev.bethge.shipir.proxy.dto.ProxyUpdateRequest;
import dev.bethge.shipir.proxy.dto.SetupBlockedReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Sets up, changes and removes the managed reverse proxy of a host. The proxy is an ordinary Compose deployment
 * ({@value ProxySyncService#PROXY_DEPLOYMENT}) built from {@link ManagedProxyTemplates}; its {@code .env} carries the
 * ACME e-mail and the DNS provider settings. After a deploy has finished the routes and certificates are pushed into
 * the container by {@link ProxySyncService}.
 *
 * <p>Writes need ADMIN and OPERATE on the host. DNS credentials exist as {@link SecretCipher} ciphertext plus a hint
 * and appear in the deployment's encrypted env only; they are never returned, audited or logged.
 */
@Service
public class ProxySetupService {

    private static final Logger log = LoggerFactory.getLogger(ProxySetupService.class);

    static final Duration DEPLOY_WAIT = Duration.ofMinutes(15);
    static final long POLL_MILLIS = 500;
    /** Compose project name + volume name of the template's config volume. */
    static final String CONFIG_VOLUME = ProxySyncService.PROXY_DEPLOYMENT + "_shipir-proxy-config";
    private static final List<Integer> PROXY_PORTS = List.of(80, 443);

    private final ProxyInstanceRepository instances;
    private final ProxyRouteRepository routes;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentService deployments;
    private final DeploymentRunService runs;
    private final ManagedProxyTemplates templates;
    private final ProxySyncService sync;
    private final ProxyHostAccess access;
    private final HostResourcesService resources;
    private final HostPortsService ports;
    private final NetworkService networks;
    private final VolumeService volumes;
    private final SecretCipher cipher;

    ProxySetupService(ProxyInstanceRepository instances, ProxyRouteRepository routes,
                      DeploymentRepository deploymentRepository, DeploymentService deployments, DeploymentRunService runs,
                      ManagedProxyTemplates templates, ProxySyncService sync, ProxyHostAccess access,
                      HostResourcesService resources, HostPortsService ports, NetworkService networks, VolumeService volumes, SecretCipher cipher) {
        this.instances = instances;
        this.routes = routes;
        this.deploymentRepository = deploymentRepository;
        this.deployments = deployments;
        this.runs = runs;
        this.templates = templates;
        this.sync = sync;
        this.access = access;
        this.resources = resources;
        this.ports = ports;
        this.networks = networks;
        this.volumes = volumes;
        this.cipher = cipher;
    }

    /** The managed proxy, or why none can be set up. Any role that sees the host. */
    public ProxyManagedResponse view(UUID hostId) {
        access.requireView(hostId);
        return instances.findByHostId(hostId)
                .map(i -> new ProxyManagedResponse(toView(i), null))
                .orElseGet(() -> new ProxyManagedResponse(null, blockedReason(hostId)));
    }

    @Auditable(action = "PROXY_SETUP", targetType = "PROXY", targetId = "#hostId",
            details = "{'type': #request.type()?.name(), 'defaultCertMode': #request.defaultCertMode()?.name()}")
    public ProxySetupResponse setup(UUID hostId, ProxySetupRequest request, String username) {
        access.requireAdminOperate(hostId);
        ProxySetupRules.validate(request);
        return sync.exclusive(hostId, () -> setupLocked(hostId, request, username));
    }

    private ProxySetupResponse setupLocked(UUID hostId, ProxySetupRequest r, String username) {
        if (instances.findByHostId(hostId).isPresent()) {
            throw new ProxyException(HttpStatus.CONFLICT, "PROXY_EXISTS", "This environment already has a managed proxy");
        }
        SetupBlockedReason blocked = blockedReason(hostId);
        // UNKNOWN only occurs for callers who do not see the whole host; a setup needs ADMIN, who always does
        if (blocked != null && blocked != SetupBlockedReason.UNKNOWN) {
            throw new ProxyException(HttpStatus.CONFLICT, "PROXY_SETUP_BLOCKED",
                    blocked == SetupBlockedReason.EXISTING_PROXY
                            ? "Another reverse proxy already runs on this environment"
                            : "Port 80 or 443 is already in use on this environment",
                    List.of(blocked.name()));
        }
        Map<String, String> credentials = r.dnsCredentials() == null ? Map.of() : r.dnsCredentials();
        String email = blankToNull(r.acmeEmail());
        ProxyInstance instance = ProxyInstance.create(hostId, r.type(), email, r.defaultCertMode());
        if (!credentials.isEmpty()) {
            instance.setDnsCredentials(r.dnsProvider(), encrypt(ProxyEnv.serialize(credentials)), ProxyEnv.hint(credentials));
        } else {
            instance.setDnsCredentials(r.dnsProvider(), null, null);
        }
        DockerEndpoint endpoint = access.endpoint(hostId);
        ensureNetwork(hostId, endpoint);
        try {
            instance = instances.saveAndFlush(instance);
        } catch (DataIntegrityViolationException e) {
            throw new ProxyException(HttpStatus.CONFLICT, "PROXY_EXISTS", "This environment already has a managed proxy");
        }
        DeploymentDetailResponse deployment;
        try {
            deployment = deployments.createSystemManaged(hostId, new DeploymentCreateRequest(ProxySyncService.PROXY_DEPLOYMENT,
                    templates.yaml(r.type()), ProxyEnv.entries(r.type(), email, r.dnsProvider(), credentials)), username);
        } catch (DeploymentException e) {
            instances.delete(instance);
            throw fromDeployment(e);
        }
        instance.linkDeployment(deployment.id());
        instance = instances.save(instance);
        DeploymentRunResponse run;
        try {
            run = runs.deploy(hostId, deployment.id(), username);
        } catch (DeploymentException e) {
            rollbackSetup(hostId, deployment.id(), instance);
            throw fromDeployment(e);
        }
        syncWhenDeployed(hostId, deployment.id());
        return new ProxySetupResponse(toView(instance), run.runId());
    }

    @Auditable(action = "PROXY_UPDATE", targetType = "PROXY", targetId = "#hostId",
            details = "{'defaultCertMode': #request.defaultCertMode()?.name(), 'dnsCredentialsReplaced': "
                    + "#request.dnsCredentials() != null && !#request.dnsCredentials().isEmpty(), "
                    + "'dnsCredentialsRemoved': #request.removeDnsCredentials() == true}")
    public ProxySetupResponse update(UUID hostId, ProxyUpdateRequest request, String username) {
        access.requireAdminOperate(hostId);
        ProxySetupRules.validate(request);
        return sync.exclusive(hostId, () -> updateLocked(hostId, request, username));
    }

    private ProxySetupResponse updateLocked(UUID hostId, ProxyUpdateRequest r, String username) {
        ProxyInstance instance = instances.findByHostId(hostId).orElseThrow(ProxySetupService::notFound);
        ProxyType type = instance.getType();
        boolean replace = r.dnsCredentials() != null && !r.dnsCredentials().isEmpty();
        boolean remove = Boolean.TRUE.equals(r.removeDnsCredentials());

        String email = r.acmeEmail() == null ? instance.getAcmeEmail() : blankToNull(r.acmeEmail());
        CertMode mode = r.defaultCertMode() == null ? instance.getDefaultCertMode() : r.defaultCertMode();
        ProxySetupRules.validateEmail(type, email);
        ProxySetupRules.validateMode(type, mode);
        if (type == ProxyType.CADDY && (replace || remove || r.dnsProvider() != null)) {
            throw new ProxyException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FOR_PROXY",
                    "DNS provider credentials are only supported with Traefik");
        }

        String provider = remove ? null : r.dnsProvider() != null ? r.dnsProvider() : instance.getDnsProvider();
        Map<String, String> credentials = remove ? Map.of()
                : replace ? r.dnsCredentials() : storedCredentials(instance);
        if (!remove && !replace && !Objects.equals(provider, instance.getDnsProvider())) {
            throw new ProxyException(HttpStatus.BAD_REQUEST, "DNS_CREDENTIALS_REQUIRED",
                    "Changing the DNS provider needs its credentials");
        }
        boolean dnsNeeded = mode == CertMode.ACME_DNS || routes.findByHostId(hostId).stream()
                .anyMatch(rt -> rt.getCertMode() == CertMode.ACME_DNS);
        if (dnsNeeded && (provider == null || credentials.isEmpty())) {
            throw new ProxyException(remove ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST,
                    remove ? "DNS_IN_USE" : "DNS_CREDENTIALS_REQUIRED",
                    "The DNS challenge is in use and needs a DNS provider and its credentials");
        }

        boolean staticChanged = type == ProxyType.TRAEFIK && (!Objects.equals(email, instance.getAcmeEmail())
                || !Objects.equals(provider, instance.getDnsProvider()) || replace || remove);
        if (staticChanged && instance.getDeploymentId() == null) {
            throw new ProxyException(HttpStatus.CONFLICT, "PROXY_DEPLOYMENT_MISSING",
                    "The proxy deployment no longer exists; remove the proxy and set it up again");
        }
        byte[] encryptedCredentials = replace ? encrypt(ProxyEnv.serialize(credentials)) : null;
        // Redeploy first, persist after: if the compose update or the deploy fails, the stored state still differs
        // from what runs, so a retry with the same body recomputes staticChanged and redeploys again.
        String runId = null;
        UUID redeployed = null;
        if (staticChanged) {
            UUID deploymentId = instance.getDeploymentId();
            try {
                String yaml = deployments.get(hostId, deploymentId).yaml();
                deployments.update(hostId, deploymentId, new DeploymentUpdateRequest(yaml,
                        ProxyEnv.entries(type, email, provider, credentials)), username);
                runId = runs.deploy(hostId, deploymentId, username).runId();
            } catch (DeploymentException e) {
                throw fromDeployment(e);
            }
            redeployed = deploymentId;
        }
        instance.configure(email, mode);
        if (replace) {
            instance.setDnsCredentials(provider, encryptedCredentials, ProxyEnv.hint(credentials));
        } else if (remove) {
            instance.setDnsCredentials(null, null, null);
        }
        instance = instances.save(instance);
        if (redeployed != null) {
            syncWhenDeployed(hostId, redeployed);
        } else {
            sync.sync(hostId);
        }
        return new ProxySetupResponse(toView(instance), runId);
    }

    /**
     * Stops and deletes the proxy deployment, detaches the routed containers from the proxy network and deletes the
     * instance with its routes. Uploaded certificates stay. The config volume (it holds the decrypted certificate keys) is deleted; the ACME data
     * volume and the network stay.
     */
    @Auditable(action = "PROXY_REMOVE", targetType = "PROXY", targetId = "#hostId")
    public void remove(UUID hostId) {
        access.requireAdminOperate(hostId);
        sync.exclusive(hostId, () -> {
            removeLocked(hostId);
            return null;
        });
    }

    private void removeLocked(UUID hostId) {
        ProxyInstance instance = instances.findByHostId(hostId).orElseThrow(ProxySetupService::notFound);
        if (instance.getDeploymentId() != null) {
            try {
                deployments.delete(hostId, instance.getDeploymentId());
            } catch (DeploymentException e) {
                if (!"DEPLOYMENT_NOT_FOUND".equals(e.code())) {
                    throw fromDeployment(e);
                }
            }
        }
        List<ProxyRoute> hostRoutes = routes.findByHostId(hostId);
        DockerEndpoint endpoint = access.endpoint(hostId);
        Set<String> containerNames = new LinkedHashSet<>();
        hostRoutes.forEach(rt -> containerNames.add(rt.getContainerName()));
        for (String container : containerNames) {
            try {
                networks.disconnectContainer(hostId, endpoint, ProxySyncService.PROXY_NETWORK, container);
            } catch (DockerConnectionException e) {
                if (e.getCode() != DockerErrorCode.NOT_FOUND) {
                    log.warn("Could not detach container {} from {} on host {}: {}", container,
                            ProxySyncService.PROXY_NETWORK, hostId, e.getCode());
                }
            }
        }
        routes.deleteAll(hostRoutes);
        instances.delete(instance);
        removeConfigVolume(hostId, endpoint);
    }

    /** The config volume holds the decrypted certificate keys; the ACME data volume stays. Best effort. */
    private void removeConfigVolume(UUID hostId, DockerEndpoint endpoint) {
        try {
            volumes.removeVolume(hostId, endpoint, CONFIG_VOLUME);
        } catch (DockerConnectionException e) {
            if (e.getCode() != DockerErrorCode.NOT_FOUND) {
                log.warn("Could not remove volume {} on host {}: {}", CONFIG_VOLUME, hostId, e.getCode());
            }
        }
    }

    /** EXISTING_PROXY, PORTS_IN_USE, UNKNOWN (caller does not see the whole host) or null (a setup is possible). */
    private SetupBlockedReason blockedReason(UUID hostId) {
        try {
            ProxyDetectionResponse detected = resources.proxy(hostId);
            if (!detected.visible()) {
                return SetupBlockedReason.UNKNOWN;
            }
            if (detected.proxy() != null) {
                return SetupBlockedReason.EXISTING_PROXY;
            }
            HostPortsResponse report = ports.report(hostId, "80,443");
            boolean busy = report.suggestions().stream()
                    .anyMatch(s -> PROXY_PORTS.contains(s.preferred()) && !Integer.valueOf(s.preferred()).equals(s.free()));
            return busy ? SetupBlockedReason.PORTS_IN_USE : null;
        } catch (ProxyException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e instanceof CodedException coded) {
                // HostException: its status is not visible here; the codes it uses for "not there" are these
                HttpStatus status = switch (coded.code()) {
                    case "HOST_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
                    default -> HttpStatus.BAD_GATEWAY;
                };
                throw new ProxyException(status, coded.code(), e.getMessage());
            }
            throw e;
        }
    }

    private void ensureNetwork(UUID hostId, DockerEndpoint endpoint) {
        try {
            boolean exists = networks.list(hostId, endpoint).stream()
                    .anyMatch(n -> ProxySyncService.PROXY_NETWORK.equals(n.name()));
            if (!exists) {
                networks.createNetwork(hostId, endpoint,
                        new NetworkCreateSpec(ProxySyncService.PROXY_NETWORK, null, false, null));
            }
        } catch (DockerConnectionException e) {
            if (e.getCode() != DockerErrorCode.NAME_TAKEN) {
                throw access.dockerFailure(e);
            }
        }
    }

    /**
     * Waits (on a virtual thread) until the deployment run has finished, then syncs if the deployment is running. A
     * failed sync is logged; the next change or an update of the proxy retries it.
     */
    private void syncWhenDeployed(UUID hostId, UUID deploymentId) {
        Thread.ofVirtual().name("proxy-sync-" + hostId).start(() -> SystemScope.run(() -> {
            try {
                if (!awaitRun(deploymentId)) {
                    log.warn("Proxy deployment {} did not finish in time; configuration not synced", deploymentId);
                    return;
                }
                DeploymentStatus status = deploymentRepository.findById(deploymentId)
                        .map(d -> d.getStatus()).orElse(null);
                if (status != DeploymentStatus.RUNNING) {
                    log.warn("Proxy deployment {} ended as {}; configuration not synced", deploymentId, status);
                    return;
                }
                sync.sync(hostId);
            } catch (RuntimeException e) {
                log.warn("Proxy configuration of host {} could not be synced after the deploy: {}", hostId, e.toString());
            }
        }));
    }

    private boolean awaitRun(UUID deploymentId) {
        long deadline = System.nanoTime() + DEPLOY_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                // the lock is held for the length of a run; getting it means the run is over
                deployments.whileNoRunInProgress(deploymentId, () -> null);
                return true;
            } catch (DeploymentException e) {
                if (!"DEPLOYMENT_IN_PROGRESS".equals(e.code())) {
                    throw e;
                }
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void rollbackSetup(UUID hostId, UUID deploymentId, ProxyInstance instance) {
        try {
            deployments.delete(hostId, deploymentId);
        } catch (RuntimeException e) {
            log.warn("Could not remove proxy deployment {} after a failed start: {}", deploymentId, e.toString());
        }
        instances.delete(instance);
    }

    private Map<String, String> storedCredentials(ProxyInstance instance) {
        byte[] encrypted = instance.getDnsCredentialsEncrypted();
        if (encrypted == null) {
            return Map.of();
        }
        try {
            return ProxyEnv.parse(cipher.decrypt(encrypted));
        } catch (EncryptionKeyMissingException e) {
            throw keyMissing();
        } catch (SecretDecryptionException e) {
            throw new ProxyException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored DNS credentials could not be decrypted");
        }
    }

    private byte[] encrypt(String text) {
        try {
            return cipher.encrypt(text);
        } catch (EncryptionKeyMissingException e) {
            throw keyMissing();
        }
    }

    private ProxyInstanceView toView(ProxyInstance i) {
        DeploymentStatus status = i.getDeploymentId() == null ? null
                : deploymentRepository.findById(i.getDeploymentId()).map(d -> d.getStatus()).orElse(null);
        return new ProxyInstanceView(i.getType(), i.getAcmeEmail(), i.getDefaultCertMode(), i.getDnsProvider(),
                i.getDnsCredentialsHint(), i.getDeploymentId(), status);
    }

    private static ProxyException fromDeployment(DeploymentException e) {
        // Compose output can echo interpolated .env values (DNS credentials): the message of a DeploymentException
        // is a fixed text, its detail lines are compose output and are not passed on.
        return new ProxyException(e.status(), e.code(), e.getMessage());
    }

    private static ProxyException keyMissing() {
        return new ProxyException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                "DNS credentials need shipir.encryption-key (SHIPIR_ENCRYPTION_KEY) to be stored");
    }

    private static ProxyException notFound() {
        return new ProxyException(HttpStatus.NOT_FOUND, "PROXY_NOT_FOUND", "No managed proxy on this environment");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
