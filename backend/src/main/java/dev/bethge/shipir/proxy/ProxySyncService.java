package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ExecHandle;
import dev.bethge.shipir.docker.client.ExecService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerFileWrite;
import dev.bethge.shipir.proxy.render.ProxyConfigRenderer;
import dev.bethge.shipir.proxy.render.RenderedFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes the proxy configuration of a host into its proxy container: the files rendered from the routes, the custom
 * file (always, even when empty, because the Caddyfile imports it and Caddy refuses to load without it) and the
 * uploaded certificates with their keys (decrypted in memory only, never logged). Then Caddy is told to reload;
 * Traefik watches its directory. Files go in through the Docker archive API, so they also reach a stopped container.
 *
 * <p>Syncs and other changes of a host's proxy are serialized per host ({@link #exclusive}); the lock is reentrant.
 * Every failure is a 502 {@code PROXY_SYNC_FAILED} carrying the proxy's own message where there is one.
 */
@Service
public class ProxySyncService {

    /** Name of the Docker network the proxy and its backends share. */
    public static final String PROXY_NETWORK = "shipir-proxy";
    /** Name (= Compose project) of the proxy deployment. */
    public static final String PROXY_DEPLOYMENT = "shipir-proxy";

    static final String SYNC_FAILED = "PROXY_SYNC_FAILED";
    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int STOP_TIMEOUT_SECONDS = 10;
    private static final int MAX_MESSAGE = 500;
    private static final long RELOAD_TIMEOUT_SECONDS = 30;
    private static final Pattern EXIT_MARKER = Pattern.compile("shipir-exit:(\\d+)");
    /**
     * Fixed command, no input is interpolated: the marker lets us read the exit status, which a TTY exec session does
     * not report, and proves that all output arrived before we look at it.
     */
    private static final List<String> CADDY_RELOAD = List.of("sh", "-c",
            "caddy reload --config " + ProxyConfigRenderer.CONFIG_DIR + "/Caddyfile --adapter caddyfile; echo \"shipir-exit:$?\"");

    private final ProxyInstanceRepository instances;
    private final ProxyRouteRepository routes;
    private final ProxyCertificateRepository certificates;
    private final Map<ProxyType, ProxyConfigRenderer> renderers = new EnumMap<>(ProxyType.class);
    private final SecretCipher cipher;
    private final ContainerService containers;
    private final ContainerFileService files;
    private final ExecService exec;
    private final ProxyHostAccess access;
    private final ConcurrentMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    ProxySyncService(ProxyInstanceRepository instances, ProxyRouteRepository routes, ProxyCertificateRepository certificates,
                     List<ProxyConfigRenderer> renderers, SecretCipher cipher, ContainerService containers,
                     ContainerFileService files, ExecService exec, ProxyHostAccess access) {
        this.instances = instances;
        this.routes = routes;
        this.certificates = certificates;
        renderers.forEach(r -> this.renderers.put(r.type(), r));
        this.cipher = cipher;
        this.containers = containers;
        this.files = files;
        this.exec = exec;
        this.access = access;
    }

    /** Pushes the stored proxy configuration of the host into its proxy container. No permission check: callers did it. */
    public void sync(UUID hostId) {
        exclusive(hostId, () -> {
            doSync(hostId);
            return null;
        });
    }

    /** Runs {@code action} while holding the host's proxy lock. */
    public <T> T exclusive(UUID hostId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(hostId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void doSync(UUID hostId) {
        ProxyInstance instance = instances.findByHostId(hostId).orElseThrow(
                () -> new ProxyException(HttpStatus.NOT_FOUND, "PROXY_NOT_FOUND", "No managed proxy on this environment"));
        List<ContainerFileWrite> writes = buildWrites(instance, routes.findByHostId(hostId),
                certificates.findByHostId(hostId));
        DockerEndpoint endpoint = access.endpoint(hostId);
        try {
            ContainerDto proxy = findContainer(hostId, endpoint, instance.getType());
            boolean running = "running".equals(proxy.state());
            boolean stoppedByUs = false;
            if ("restarting".equals(proxy.state())) {
                // the archive API refuses a container that is restarting (e.g. Caddy without a Caddyfile yet)
                containers.stopContainer(hostId, endpoint, proxy.id(), STOP_TIMEOUT_SECONDS);
                stoppedByUs = true;
            }
            files.writeFiles(hostId, endpoint, proxy.id(), ProxyConfigRenderer.CONFIG_DIR, writes, MAX_BYTES);
            if (running && instance.getType() == ProxyType.CADDY) {
                reloadCaddy(hostId, endpoint, proxy.id());
            } else if (!running && (stoppedByUs || instance.getType() == ProxyType.CADDY)) {
                containers.restartContainer(hostId, endpoint, proxy.id(), STOP_TIMEOUT_SECONDS);
            }
        } catch (DockerConnectionException e) {
            throw failed("The proxy configuration could not be written (" + e.getCode() + ")");
        }
    }

    /** Certificates first, then the custom file, the generated file last (it is the one that refers to the rest). */
    private List<ContainerFileWrite> buildWrites(ProxyInstance instance, List<ProxyRoute> routeList,
                                                 List<ProxyCertificate> certificateList) {
        Map<UUID, ProxyCertificate> byId = certificateList.stream()
                .collect(Collectors.toMap(ProxyCertificate::getId, c -> c));
        ProxyConfigRenderer renderer = renderers.get(instance.getType());
        List<RenderedFile> rendered;
        try {
            rendered = renderer.render(instance, routeList, byId);
        } catch (IllegalArgumentException e) {
            throw new ProxyException(HttpStatus.UNPROCESSABLE_ENTITY, SYNC_FAILED,
                    "The proxy configuration could not be generated: " + e.getMessage());
        }
        List<ContainerFileWrite> writes = new ArrayList<>();
        for (ProxyCertificate c : certificateList) {
            writes.add(new ContainerFileWrite("certs/" + c.getId() + ".crt", utf8(c.getCertPem()), 0644));
            writes.add(new ContainerFileWrite("certs/" + c.getId() + ".key", utf8(decryptKey(c)), 0600));
        }
        writes.add(new ContainerFileWrite(customFileName(instance.getType()), utf8(instance.getCustomConfig()), 0644));
        for (RenderedFile f : rendered) {
            writes.add(new ContainerFileWrite(f.name(), utf8(f.content()), 0644));
        }
        return writes;
    }

    /** Name of the user-editable file next to the generated one; Caddy imports it, Traefik loads the whole directory. */
    static String customFileName(ProxyType type) {
        return type == ProxyType.CADDY ? "custom.caddy" : "custom.yml";
    }

    private String decryptKey(ProxyCertificate c) {
        try {
            return cipher.decrypt(c.getKeyEncrypted());
        } catch (EncryptionKeyMissingException e) {
            throw new ProxyException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                    "No encryption key is configured, certificate keys cannot be read");
        } catch (SecretDecryptionException e) {
            throw failed("The key of certificate " + c.getName() + " could not be decrypted");
        }
    }

    private ContainerDto findContainer(UUID hostId, DockerEndpoint endpoint, ProxyType type) {
        String service = type == ProxyType.CADDY ? "caddy" : "traefik";
        List<ContainerDto> matching = containers.listContainers(hostId, endpoint).stream()
                .filter(c -> PROXY_DEPLOYMENT.equals(c.composeProject()) && service.equals(c.composeService()))
                .toList();
        return matching.stream().filter(c -> "running".equals(c.state())).findFirst()
                .or(() -> matching.stream().findFirst())
                .orElseThrow(() -> failed("The proxy container does not exist; deploy the proxy again"));
    }

    private void reloadCaddy(UUID hostId, DockerEndpoint endpoint, String containerId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        ExecHandle handle = exec.open(hostId, endpoint, containerId, CADDY_RELOAD, 200, 24, bytes -> {
            synchronized (out) {
                if (out.size() < 16 * 1024) {
                    out.writeBytes(bytes);
                }
            }
        }, failure -> {
            if (failure == null) {
                ended.complete(null);
            } else {
                ended.completeExceptionally(failure);
            }
        });
        try {
            ended.get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw failed("Caddy did not answer to the reload in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed("The reload was interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw failed("The reload could not be run");
        } finally {
            handle.close();
        }
        String output;
        synchronized (out) {
            output = out.toString(StandardCharsets.UTF_8);
        }
        Matcher marker = EXIT_MARKER.matcher(output);
        if (!marker.find() || !"0".equals(marker.group(1))) {
            throw failed("Caddy rejected the configuration: " + printable(EXIT_MARKER.matcher(output).replaceAll("")));
        }
    }

    private static ProxyException failed(String message) {
        return new ProxyException(HttpStatus.BAD_GATEWAY, SYNC_FAILED, message);
    }

    /** Control characters out, whitespace collapsed, capped. */
    private static String printable(String s) {
        String clean = s.replaceAll("\\e\\[[0-9;]*[A-Za-z]", "").replaceAll("[\\p{Cntrl}&&[^\\n]]", "")
                .replaceAll("\\s*\\n\\s*", " | ").trim();
        clean = clean.replaceAll("^(\\| )+|( \\|)+$", "");
        return clean.length() <= MAX_MESSAGE ? clean : clean.substring(0, MAX_MESSAGE) + "...";
    }

    private static byte[] utf8(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    }
}
