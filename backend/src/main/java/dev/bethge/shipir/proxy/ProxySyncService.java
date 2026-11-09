package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.ViewerErrorCodes;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ExecHandle;
import dev.bethge.shipir.docker.client.ExecService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerFileWrite;
import dev.bethge.shipir.proxy.render.ProxyConfigRenderer;
import dev.bethge.shipir.proxy.render.RenderedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private static final Logger log = LoggerFactory.getLogger(ProxySyncService.class);
    static final String SYNC_FAILED = "PROXY_SYNC_FAILED";
    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int STOP_TIMEOUT_SECONDS = 10;
    private static final int MAX_MESSAGE = 500;
    private static final long RELOAD_TIMEOUT_SECONDS = 30;
    private static final Pattern EXIT_MARKER = Pattern.compile("shipir-exit:(\\d{1,5})\\s*$");
    private static final int OUTPUT_TAIL_BYTES = 16 * 1024;
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
    /** Hosts whose proxy container Shipir stopped and could not start again; the next sync starts it (not user-stopped ones). */
    private final Set<UUID> stoppedByShipir = ConcurrentHashMap.newKeySet();

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

    /**
     * Removes the files of deleted certificates (their private keys!) from the proxy container. The archive API cannot
     * delete, so each file is first overwritten with empty content (works on a stopped container and destroys the key
     * material for sure), then, if the container runs, unlinked with a fixed {@code rm -f} argument list (no shell; the
     * paths are built from UUIDs only). Nothing to do when there is no proxy or no container. Failure to write is a
     * 502 {@code PROXY_SYNC_FAILED}; failure to unlink only leaves empty files and is logged.
     */
    public void purgeCertificateFiles(UUID hostId, Collection<UUID> certificateIds) {
        if (certificateIds.isEmpty()) {
            return;
        }
        exclusive(hostId, () -> {
            Optional<ProxyInstance> instance = instances.findByHostId(hostId);
            if (instance.isEmpty()) {
                return null;
            }
            DockerEndpoint endpoint = access.endpoint(hostId);
            try {
                Optional<ContainerDto> located = locateContainer(hostId, endpoint, instance.get().getType());
                if (located.isEmpty()) {
                    return null; // no proxy container, so no key files on a volume we can reach
                }
                ContainerDto proxy = located.get();
                List<ContainerFileWrite> blanks = new ArrayList<>();
                List<String> command = new ArrayList<>(List.of("rm", "-f", "--"));
                for (UUID id : certificateIds) {
                    blanks.add(new ContainerFileWrite("certs/" + id + ".crt", new byte[0], 0600));
                    blanks.add(new ContainerFileWrite("certs/" + id + ".key", new byte[0], 0600));
                    command.add(ProxyConfigRenderer.CONFIG_DIR + "/certs/" + id + ".crt");
                    command.add(ProxyConfigRenderer.CONFIG_DIR + "/certs/" + id + ".key");
                }
                boolean stoppedByUs = false;
                if ("restarting".equals(proxy.state())) {
                    // the archive API refuses a restarting container, as in doSync
                    containers.stopContainer(hostId, endpoint, proxy.id(), STOP_TIMEOUT_SECONDS);
                    stoppedByUs = true;
                }
                writeFiles(hostId, endpoint, proxy.id(), blanks, stoppedByUs);
                if ("running".equals(proxy.state())) {
                    unlink(hostId, endpoint, proxy.id(), command);
                } else if (stoppedByUs) {
                    containers.restartContainer(hostId, endpoint, proxy.id(), STOP_TIMEOUT_SECONDS);
                }
            } catch (DockerConnectionException e) {
                throw failed("The certificate files could not be removed from the proxy (" + ViewerErrorCodes.forViewer(e.getCode(), access.isAdmin()) + ")");
            }
            return null;
        });
    }

    private void unlink(UUID hostId, DockerEndpoint endpoint, String containerId, List<String> command) {
        CompletableFuture<Void> ended = new CompletableFuture<>();
        ExecHandle handle = null;
        try {
            handle = exec.open(hostId, endpoint, containerId, command, 200, 24, bytes -> { }, failure -> {
                if (failure == null) {
                    ended.complete(null);
                } else {
                    ended.completeExceptionally(failure);
                }
            });
            ended.get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Could not unlink the emptied certificate files on host {}: {}", hostId, e.getClass().getSimpleName());
        } finally {
            if (handle != null) {
                handle.close();
            }
        }
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
            boolean stoppedByUs = stoppedByShipir.contains(hostId) && !running;
            if ("restarting".equals(proxy.state())) {
                // the archive API refuses a container that is restarting (e.g. Caddy without a Caddyfile yet)
                containers.stopContainer(hostId, endpoint, proxy.id(), STOP_TIMEOUT_SECONDS);
                stoppedByUs = true;
            }
            writeFiles(hostId, endpoint, proxy.id(), writes, stoppedByUs);
            if (running && instance.getType() == ProxyType.CADDY) {
                reloadCaddy(hostId, endpoint, proxy.id());
            } else if (!running && (stoppedByUs || instance.getType() == ProxyType.CADDY)) {
                containers.restartContainer(hostId, endpoint, proxy.id(), STOP_TIMEOUT_SECONDS);
            }
            stoppedByShipir.remove(hostId);
        } catch (DockerConnectionException e) {
            throw failed("The proxy configuration could not be written (" + ViewerErrorCodes.forViewer(e.getCode(), access.isAdmin()) + ")");
        }
    }

    /**
     * Writes into the proxy container. If the write fails after we stopped a restarting container, it is started again
     * here, so a failed sync or purge does not leave the proxy down. A failing start is logged and attached to the write
     * failure, which is what the caller sees.
     */
    private void writeFiles(UUID hostId, DockerEndpoint endpoint, String containerId, List<ContainerFileWrite> writes,
                            boolean stoppedByUs) {
        try {
            files.writeFiles(hostId, endpoint, containerId, ProxyConfigRenderer.CONFIG_DIR, writes, MAX_BYTES);
        } catch (RuntimeException writeFailure) {
            if (stoppedByUs) {
                try {
                    containers.restartContainer(hostId, endpoint, containerId, STOP_TIMEOUT_SECONDS);
                    stoppedByShipir.remove(hostId);
                } catch (RuntimeException startFailure) {
                    stoppedByShipir.add(hostId);
                    writeFailure.addSuppressed(startFailure);
                    log.warn("Could not start the proxy container again after a failed write on host {}: {}", hostId,
                            startFailure.getClass().getSimpleName());
                }
            }
            throw writeFailure;
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
        return locateContainer(hostId, endpoint, type)
                .orElseThrow(() -> failed("The proxy container does not exist; deploy the proxy again"));
    }

    private Optional<ContainerDto> locateContainer(UUID hostId, DockerEndpoint endpoint, ProxyType type) {
        String service = type == ProxyType.CADDY ? "caddy" : "traefik";
        List<ContainerDto> matching = containers.listContainers(hostId, endpoint).stream()
                .filter(c -> PROXY_DEPLOYMENT.equals(c.composeProject()) && service.equals(c.composeService()))
                .toList();
        return matching.stream().filter(c -> "running".equals(c.state())).findFirst()
                .or(() -> matching.stream().findFirst());
    }

    private void reloadCaddy(UUID hostId, DockerEndpoint endpoint, String containerId) {
        ExecOutcome result = runMarked(hostId, endpoint, containerId, CADDY_RELOAD);
        if (result.exitCode() < 0) {
            throw failed("Caddy gave no result to the reload" + (result.output().isEmpty() ? "" : ": " + result.output()));
        }
        if (result.exitCode() != 0) {
            throw failed("Caddy rejected the configuration: " + result.output());
        }
    }

    /** Result of {@link #execInProxy}: the exit status ({@code -1} when the final marker is missing, e.g. cut-off output) and cleaned output. */
    public record ExecOutcome(int exitCode, String output) {
    }

    /**
     * Runs a command inside the running proxy container, as an argument list (no shell sees {@code args}: they are
     * positional parameters of a fixed {@code sh -c} script that only adds the exit marker). Used for Caddy's own
     * validation of a configuration. A failure to run it at all is a 502 {@code PROXY_SYNC_FAILED}.
     */
    public ExecOutcome execInProxy(UUID hostId, ProxyType type, List<String> args) {
        DockerEndpoint endpoint = access.endpoint(hostId);
        try {
            ContainerDto proxy = findContainer(hostId, endpoint, type);
            if (!"running".equals(proxy.state())) {
                throw failed("The proxy container is not running");
            }
            List<String> command = new ArrayList<>(List.of("sh", "-c", "\"$@\"; echo \"shipir-exit:$?\"", "sh"));
            command.addAll(args);
            return runMarked(hostId, endpoint, proxy.id(), command);
        } catch (DockerConnectionException e) {
            throw failed("The proxy could not be reached (" + ViewerErrorCodes.forViewer(e.getCode(), access.isAdmin()) + ")");
        }
    }

    /** Id of the proxy container of the host (a running one preferred); 502 {@code PROXY_SYNC_FAILED} when there is none. */
    public String proxyContainerId(UUID hostId, ProxyType type) {
        try {
            return findContainer(hostId, access.endpoint(hostId), type).id();
        } catch (DockerConnectionException e) {
            throw failed("The proxy could not be reached (" + ViewerErrorCodes.forViewer(e.getCode(), access.isAdmin()) + ")");
        }
    }

    private ExecOutcome runMarked(UUID hostId, DockerEndpoint endpoint, String containerId, List<String> command) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        ExecHandle handle = exec.open(hostId, endpoint, containerId, command, 200, 24, bytes -> {
            synchronized (out) {
                // keep the tail: the exit marker is the last thing the script prints
                out.writeBytes(bytes);
                if (out.size() > 2 * OUTPUT_TAIL_BYTES) {
                    byte[] all = out.toByteArray();
                    out.reset();
                    out.write(all, all.length - OUTPUT_TAIL_BYTES, OUTPUT_TAIL_BYTES);
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
            throw failed("The proxy did not answer in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed("The command was interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw failed("The command could not be run");
        } finally {
            handle.close();
        }
        String output;
        synchronized (out) {
            output = out.toString(StandardCharsets.UTF_8);
        }
        // Only the marker at the very end counts: the proxy echoes parts of the user's file in its error messages, so a
        // "shipir-exit:0" earlier in the output may come from the content being validated.
        Matcher marker = EXIT_MARKER.matcher(output);
        if (!marker.find()) {
            return new ExecOutcome(-1, printable(output));
        }
        return new ExecOutcome(Integer.parseInt(marker.group(1)), printable(output.substring(0, marker.start())));
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
