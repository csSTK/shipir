package dev.bethge.shipir.proxy.config;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.ProxyCertificate;
import dev.bethge.shipir.proxy.ProxyCertificateRepository;
import dev.bethge.shipir.proxy.ProxyException;
import dev.bethge.shipir.proxy.ProxyHostAccess;
import dev.bethge.shipir.proxy.ProxyInstance;
import dev.bethge.shipir.proxy.ProxyInstanceRepository;
import dev.bethge.shipir.proxy.ProxyRouteRepository;
import dev.bethge.shipir.proxy.ProxySyncService;
import dev.bethge.shipir.proxy.config.dto.CustomConfigRequest;
import dev.bethge.shipir.proxy.config.dto.CustomConfigVersionView;
import dev.bethge.shipir.proxy.config.dto.ManagedFileView;
import dev.bethge.shipir.proxy.config.dto.ProxyConfigResponse;
import dev.bethge.shipir.proxy.render.ProxyConfigRenderer;
import dev.bethge.shipir.proxy.render.RenderedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Advanced mode of the managed proxy: shows the generated (read-only) files and lets an ADMIN with OPERATE on the host
 * edit the custom file. A change is validated first (nothing reaches the proxy when that fails), the replaced content is
 * stored as a version (the last {@value #MAX_VERSIONS} are kept), then the proxy is synced. If the sync fails the stored
 * content is put back and the proxy is synced once more, best effort, so database and proxy do not drift apart.
 * Everything runs under the host's proxy lock.
 */
@Service
public class ProxyCustomConfigService {

    static final int MAX_VERSIONS = 20;
    static final String HASH_PLACEHOLDER = "[password hash hidden]";
    private static final Logger log = LoggerFactory.getLogger(ProxyCustomConfigService.class);

    private final ProxyInstanceRepository instances;
    private final ProxyRouteRepository routes;
    private final ProxyCertificateRepository certificates;
    private final ProxyCustomConfigVersionRepository versions;
    private final Map<ProxyType, ProxyConfigRenderer> renderers = new EnumMap<>(ProxyType.class);
    private final Map<ProxyType, CustomConfigValidator> validators = new EnumMap<>(ProxyType.class);
    private final ProxySyncService sync;
    private final ProxyHostAccess access;

    ProxyCustomConfigService(ProxyInstanceRepository instances, ProxyRouteRepository routes,
                             ProxyCertificateRepository certificates, ProxyCustomConfigVersionRepository versions,
                             List<ProxyConfigRenderer> renderers, List<CustomConfigValidator> validators,
                             ProxySyncService sync, ProxyHostAccess access) {
        this.instances = instances;
        this.routes = routes;
        this.certificates = certificates;
        this.versions = versions;
        renderers.forEach(r -> this.renderers.put(r.type(), r));
        validators.forEach(v -> this.validators.put(v.type(), v));
        this.sync = sync;
        this.access = access;
    }

    public ProxyConfigResponse view(UUID hostId) {
        access.requireAdminOperate(hostId);
        return toResponse(instance(hostId), render(instance(hostId)));
    }

    public List<CustomConfigVersionView> versions(UUID hostId) {
        access.requireAdminOperate(hostId);
        ProxyInstance instance = instance(hostId);
        return versions.findByProxyInstanceIdOrderByCreatedAtDescIdDesc(instance.getId()).stream()
                .map(v -> new CustomConfigVersionView(v.getId(), v.getCreatedAt(), v.getCreatedBy(), v.getContent()))
                .toList();
    }

    @Auditable(action = "PROXY_CUSTOM_CONFIG_UPDATE", targetType = "PROXY", targetId = "#hostId",
            details = "{'length': #request.content()?.length()}")
    public ProxyConfigResponse update(UUID hostId, CustomConfigRequest request, String username) {
        access.requireAdminOperate(hostId);
        return sync.exclusive(hostId, () -> apply(hostId, request.content(), username));
    }

    @Auditable(action = "PROXY_CUSTOM_CONFIG_RESTORE", targetType = "PROXY", targetId = "#hostId",
            details = "{'versionId': #versionId.toString()}")
    public ProxyConfigResponse restore(UUID hostId, UUID versionId, String username) {
        access.requireAdminOperate(hostId);
        return sync.exclusive(hostId, () -> {
            ProxyInstance instance = instance(hostId);
            ProxyCustomConfigVersion version = versions.findByIdAndProxyInstanceId(versionId, instance.getId())
                    .orElseThrow(() -> new ProxyException(HttpStatus.NOT_FOUND, "PROXY_CONFIG_VERSION_NOT_FOUND",
                            "Version not found"));
            return apply(hostId, version.getContent(), username);
        });
    }

    private ProxyConfigResponse apply(UUID hostId, String content, String username) {
        ProxyInstance instance = instance(hostId);
        List<RenderedFile> managed = render(instance);
        validators.get(instance.getType()).validate(hostId, content, managed);

        String previous = instance.getCustomConfig();
        Instant previousUpdatedAt = instance.getCustomConfigUpdatedAt();
        ProxyCustomConfigVersion backup = versions.save(new ProxyCustomConfigVersion(instance.getId(), previous, username));
        instance.setCustomConfig(content);
        instances.save(instance);
        try {
            sync.sync(hostId);
        } catch (RuntimeException e) {
            instance.revertCustomConfig(previous, previousUpdatedAt);
            instances.save(instance);
            versions.delete(backup);
            try {
                sync.sync(hostId);
            } catch (RuntimeException again) {
                log.warn("Could not restore the previous proxy configuration on host {}: {}", hostId,
                        again.getClass().getSimpleName());
            }
            throw e;
        }
        versions.prune(instance.getId(), MAX_VERSIONS);
        return toResponse(instance, managed);
    }

    private ProxyInstance instance(UUID hostId) {
        return instances.findByHostId(hostId).orElseThrow(
                () -> new ProxyException(HttpStatus.NOT_FOUND, "PROXY_NOT_FOUND", "No managed proxy on this environment"));
    }

    private List<RenderedFile> render(ProxyInstance instance) {
        Map<UUID, ProxyCertificate> byId = certificates.findByHostId(instance.getHostId()).stream()
                .collect(Collectors.toMap(ProxyCertificate::getId, c -> c));
        try {
            return renderers.get(instance.getType()).render(instance, routes.findByHostId(instance.getHostId()), byId);
        } catch (IllegalArgumentException e) {
            throw new ProxyException(HttpStatus.UNPROCESSABLE_ENTITY, "PROXY_SYNC_FAILED",
                    "The proxy configuration could not be generated: " + e.getMessage());
        }
    }

    /** Basic-auth hashes are hashed, but still credentials material: the read-only view shows a placeholder instead. */
    private List<ManagedFileView> redacted(ProxyInstance instance, List<RenderedFile> managed) {
        List<String> hashes = routes.findByHostId(instance.getHostId()).stream()
                .flatMap(r -> r.getBasicAuthUsers() == null ? java.util.stream.Stream.<String>empty()
                        : r.getBasicAuthUsers().values().stream())
                .filter(h -> h != null && !h.isBlank()).distinct().toList();
        return managed.stream().map(f -> {
            String content = f.content();
            for (String hash : hashes) {
                content = content.replace(hash, HASH_PLACEHOLDER);
            }
            return new ManagedFileView(f.name(), content);
        }).toList();
    }

    private ProxyConfigResponse toResponse(ProxyInstance instance, List<RenderedFile> managed) {
        String customFile = instance.getType() == ProxyType.CADDY ? "custom.caddy" : "custom.yml";
        return new ProxyConfigResponse(instance.getType(),
                redacted(instance, managed),
                customFile, instance.getCustomConfig(), instance.getCustomConfigUpdatedAt());
    }
}
