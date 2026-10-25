package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Helper-container approach: a container is created (never started) from the helper image with only the named
 * volume mounted at {@value #MOUNT}, no network, no capabilities, not privileged and a read-only root filesystem.
 * The daemon's archive API then reads from or writes into the mount, so no process of ours or of the image runs.
 * The volume name is matched against a strict pattern first, because the daemon would read a name containing a
 * slash as a host path (a bind mount).
 */
@Service
public class VolumeArchiveServiceImpl implements VolumeArchiveService {

    private static final Logger log = LoggerFactory.getLogger(VolumeArchiveServiceImpl.class);

    static final String HELPER_LABEL = "dev.bethge.shipir.helper";
    private static final String HELPER_LABEL_VALUE = "volume-backup";
    private static final String MOUNT = "/data";
    private static final Pattern VOLUME_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
    /** Transfers and an image pull take longer than the usual API call; the read timeout alone would cut them off. */
    private static final Duration TRANSFER_EXTRA = Duration.ofMinutes(10);

    private final DockerCallSupport calls;

    public VolumeArchiveServiceImpl(DockerHostConnectionManager manager) {
        this.calls = new DockerCallSupport(manager);
    }

    @Override
    public InputStream backup(UUID hostId, DockerEndpoint endpoint, String volumeName, String helperImage, long maxBytes) {
        validateName(volumeName);
        String helperId = calls.call(hostId, endpoint, TRANSFER_EXTRA, client -> {
            requireVolume(client, volumeName);
            ensureImage(client, helperImage);
            return createHelper(client, volumeName, helperImage, true);
        });
        try {
            InputStream raw = calls.call(hostId, endpoint, TRANSFER_EXTRA,
                    client -> client.copyArchiveFromContainerCmd(helperId, MOUNT + "/.").exec());
            return new CleanupInputStream(new ContainerFileServiceImpl.LimitedInputStream(raw, maxBytes),
                    () -> removeHelper(hostId, endpoint, helperId));
        } catch (RuntimeException e) {
            removeHelper(hostId, endpoint, helperId);
            throw e;
        }
    }

    @Override
    public void restore(UUID hostId, DockerEndpoint endpoint, String volumeName, String helperImage, ArchiveSource tar,
                        long maxBytes, boolean allowInUse) {
        validateName(volumeName);
        try (InputStream in = tar.open()) {
            VolumeArchiveValidator.validate(in, maxBytes);
        } catch (IOException e) {
            throw new DockerConnectionException(DockerErrorCode.INVALID_ARCHIVE, "The upload could not be read.", e);
        }
        String helperId = calls.call(hostId, endpoint, TRANSFER_EXTRA, client -> {
            requireVolume(client, volumeName);
            if (!allowInUse && inUse(client, volumeName)) {
                throw new DockerConnectionException(DockerErrorCode.IN_USE, "The volume is used by a container.", null);
            }
            ensureImage(client, helperImage);
            return createHelper(client, volumeName, helperImage, false);
        });
        try (InputStream in = tar.open()) {
            ContainerFileServiceImpl.LimitedInputStream limited = new ContainerFileServiceImpl.LimitedInputStream(in, maxBytes) {
                @Override
                public void close() {
                    // closed by the try-with-resources above
                }
            };
            try {
                calls.call(hostId, endpoint, TRANSFER_EXTRA, client -> {
                    client.copyArchiveToContainerCmd(helperId).withRemotePath(MOUNT).withTarInputStream(limited).exec();
                    return null;
                });
            } catch (DockerConnectionException e) {
                if (limited.exceeded()) {
                    throw new DockerConnectionException(DockerErrorCode.SIZE_LIMIT_EXCEEDED, "The transfer exceeds the size limit.", null);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "The upload could not be read.", e);
        } finally {
            removeHelper(hostId, endpoint, helperId);
        }
    }

    private static void validateName(String name) {
        if (name == null || !VOLUME_NAME.matcher(name).matches()) {
            throw new DockerConnectionException(DockerErrorCode.INVALID_VOLUME_NAME, "The volume name is not valid.", null);
        }
    }

    /** The daemon would create a missing volume implicitly on mount; refuse instead (404 via NotFoundException). */
    private static void requireVolume(DockerClient client, String name) {
        client.inspectVolumeCmd(name).exec();
    }

    private static boolean inUse(DockerClient client, String name) {
        return client.listContainersCmd().withShowAll(true).exec().stream()
                .filter(c -> c.getLabels() == null || !c.getLabels().containsKey(HELPER_LABEL))
                .map(Container::getMounts)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(ContainerMount::getName)
                .anyMatch(name::equals);
    }

    private static void ensureImage(DockerClient client, String image) {
        try {
            client.inspectImageCmd(image).exec();
        } catch (NotFoundException missing) {
            try {
                client.pullImageCmd(image).start().awaitCompletion();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DockerConnectionException(DockerErrorCode.PULL_FAILED, "The helper image could not be pulled.", e);
            } catch (RuntimeException e) {
                throw new DockerConnectionException(DockerErrorCode.PULL_FAILED, "The helper image could not be pulled.", e);
            }
        }
    }

    private static String createHelper(DockerClient client, String volume, String image, boolean readOnly) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(new Bind(volume, new Volume(MOUNT), readOnly ? AccessMode.ro : AccessMode.rw))
                .withNetworkMode("none")
                .withPrivileged(false)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withReadonlyRootfs(true);
        return client.createContainerCmd(image)
                .withCmd("true")
                .withLabels(Map.of(HELPER_LABEL, HELPER_LABEL_VALUE))
                .withHostConfig(hostConfig)
                .exec().getId();
    }

    private void removeHelper(UUID hostId, DockerEndpoint endpoint, String helperId) {
        try {
            calls.call(hostId, endpoint, client -> {
                client.removeContainerCmd(helperId).withForce(true).withRemoveVolumes(false).exec();
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Could not remove volume helper container {}: {}", helperId, e.toString());
        }
    }

    /** Runs {@code cleanup} once when the stream is closed, whether it was read to the end or not. */
    private static final class CleanupInputStream extends FilterInputStream {

        private final Runnable cleanup;
        private final AtomicBoolean done = new AtomicBoolean();

        CleanupInputStream(InputStream in, Runnable cleanup) {
            super(in);
            this.cleanup = cleanup;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (done.compareAndSet(false, true)) {
                    cleanup.run();
                }
            }
        }
    }
}
